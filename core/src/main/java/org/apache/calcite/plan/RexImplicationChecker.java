/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan;

import org.apache.calcite.DataContext;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexExecutable;
import org.apache.calcite.rex.RexExecutorImpl;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlCastFunction;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.trace.CalciteLogger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Checks whether one condition logically implies another.
 *
 * <p>If A &rArr; B, whenever A is true, B will be true also.
 *
 * <p>For example:
 * <ul>
 * <li>(x &gt; 10) &rArr; (x &gt; 5)
 * <li>(y = 10) &rArr; (y &lt; 30 OR x &gt; 30)
 * </ul>
 */
public class RexImplicationChecker {
  private static final CalciteLogger LOGGER =
      new CalciteLogger(Logger.getLogger(RexImplicationChecker.class.getName()));

  final RexBuilder builder;
  final RexExecutorImpl executor;
  final RelDataType rowType;

  public RexImplicationChecker(
      RexBuilder builder,
      RexExecutorImpl executor,
      RelDataType rowType) {
    this.builder = builder;
    this.executor = executor;
    this.rowType = rowType;
  }

  /**
   * Checks if condition first implies (&rArr;) condition second.
   *
   * <p>This reduces to SAT problem which is NP-Complete.
   * When this method says first implies second then it is definitely true.
   * But it cannot prove that first does not imply second.
   *
   * @param first first condition
   * @param second second condition
   * @return true if it can prove first &rArr; second; otherwise false i.e.,
   * it doesn't know if implication holds
   */
  public boolean implies(RexNode first, RexNode second) {
    // Validation
    if (!validate(first, second)) {
      return false;
    }

    LOGGER.fine("Checking if " + first.toString() + " => " + second.toString());

    RexCall firstCond = (RexCall) first;
    RexCall secondCond = (RexCall) second;

    // Get DNF
    RexNode firstDnf = RexUtil.toDnf(builder, first);
    RexNode secondDnf = RexUtil.toDnf(builder, second);

    // Check Trivial Cases
    if (firstDnf.isAlwaysFalse()
        || secondDnf.isAlwaysTrue()) {
      return true;
    }

    /** Decomposes DNF into List of Conjunctions.
     *
     * <p>For example,
     * {@code x > 10 AND y > 30) OR (z > 90)}
     * will be converted to
     * list of 2 conditions:
     *
     * <ul>
     *   <li>(x > 10 AND y > 30)</li>
     *   <li>z > 90</li>
     * </ul>
     */
    List<RexNode> firstDnfs = RelOptUtil.disjunctions(firstDnf);
    List<RexNode> secondDnfs = RelOptUtil.disjunctions(secondDnf);

    for (RexNode f : firstDnfs) {
      if (!f.isAlwaysFalse()) {
        // Check if f implies at least
        // one of the conjunctions in list secondDnfs
        boolean implyOneConjunction = false;
        for (RexNode s : secondDnfs) {
          if (s.isAlwaysFalse()) { // f cannot imply s
            continue;
          }

          if (impliesConjunction(f, s)) {
            // Satisfies one of the condition, so lets
            // move to next conjunction in firstDnfs
            implyOneConjunction = true;
            break;
          }
        }

        // If f could not imply even one conjunction in
        // secondDnfs, then final implication may be false
        if (!implyOneConjunction) {
          LOGGER.fine(first + " doesnot imply " + second);
          return false;
        }
      }
    }

    LOGGER.fine(first + " implies " + second);
    return true;
  }

  /** Returns whether first implies second (both are conjunctions). */
  private boolean impliesConjunction(RexNode first, RexNode second) {
    final InputUsageFinder firstUsageFinder = new InputUsageFinder();
    final InputUsageFinder secondUsageFinder = new InputUsageFinder();

    RexUtil.apply(firstUsageFinder, new ArrayList<RexNode>(), first);
    RexUtil.apply(secondUsageFinder, new ArrayList<RexNode>(), second);

    // Check Support
    if (!checkSupport(firstUsageFinder, secondUsageFinder)) {
      LOGGER.warning("Support for checking " + first
          + " => " + second + " is not there");
      return false;
    }

    ImmutableList.Builder<Set<Pair<RexInputRef, RexNode>>> usagesBuilder =
        ImmutableList.builder();
    for (Map.Entry<RexInputRef, InputRefUsage<SqlOperator, RexNode>> entry
        : firstUsageFinder.usageMap.entrySet()) {
      ImmutableSet.Builder<Pair<RexInputRef, RexNode>> usageBuilder =
          ImmutableSet.builder();
      if (entry.getValue().usageList.size() > 0) {
        for (final Pair<SqlOperator, RexNode> pair
            : entry.getValue().usageList) {
          usageBuilder.add(Pair.of(entry.getKey(), pair.getValue()));
        }
        usagesBuilder.add(usageBuilder.build());
      }
    }

    final Set<List<Pair<RexInputRef, RexNode>>> usages =
        Sets.cartesianProduct(usagesBuilder.build());

    for (List usageList : usages) {
      // Get the literals from first conjunction and executes second conjunction
      // using them.
      //
      // E.g., for
      //   x > 30 &rArr; x > 10,
      // we will replace x by 30 in second expression and execute it i.e.,
      //   30 > 10
      //
      // If it's true then we infer implication.
      final DataContext dataValues =
          VisitorDataContext.of(rowType, usageList);

      if (!isSatisfiable(second, dataValues)) {
        return false;
      }
    }

    return true;
  }

  private boolean isSatisfiable(RexNode second, DataContext dataValues) {
    if (dataValues == null) {
      return false;
    }

    ImmutableList<RexNode> constExps = ImmutableList.of(second);
    final RexExecutable exec =
        executor.getExecutable(builder, constExps, rowType);

    Object[] result;
    exec.setDataContext(dataValues);
    try {
      result = exec.execute();
    } catch (Exception e) {
      // TODO: CheckSupport should not allow this exception to be thrown
      // Need to monitor it and handle all the cases raising them.
      LOGGER.warning("Exception thrown while checking if => " + second + ": " + e.getMessage());
      return false;
    }
    return result != null
        && result.length == 1
        && result[0] instanceof Boolean
        && (Boolean) result[0];
  }

  /**
   * Looks at the usage of variables in first and second conjunction to decide
   * whether this kind of expression is currently supported for proving first
   * implies second.
   *
   * <ol>
   * <li>Variables should be used only once in both the conjunction against
   * given set of operations only: >, <, <=, >=, =, !=
   *
   * <li>All the variables used in second condition should be used even in the
   * first.
   *
   * <li>If operator used for variable in first is op1 and op2 for second, then
   * we support these combination for conjunction (op1, op2) then op1, op2
   * belongs to one of the following sets:
   *
   * <ul>
   *    <li>(<, <=) X (<, <=)      <i>note: X represents cartesian product</i>
   *    <li>(> / >=) X (>, >=)
   *    <li>(=) X (>, >=, <, <=, =, !=)
   *    <li>(!=, =)
   * </ul>
   *
   * <li>We support utmost 2 operators to be be used for a variable in first
   * and second usages
   *
   * </ol>
   *
   * @return whether input usage pattern is supported
   */
  private boolean checkSupport(InputUsageFinder firstUsageFinder,
      InputUsageFinder secondUsageFinder) {
    final Map<RexInputRef, InputRefUsage<SqlOperator, RexNode>> firstUsageMap =
        firstUsageFinder.usageMap;
    final Map<RexInputRef, InputRefUsage<SqlOperator, RexNode>> secondUsageMap =
        secondUsageFinder.usageMap;

    for (Map.Entry<RexInputRef, InputRefUsage<SqlOperator, RexNode>> entry
        : secondUsageMap.entrySet()) {
      final InputRefUsage<SqlOperator, RexNode> secondUsage = entry.getValue();
      final List<Pair<SqlOperator, RexNode>> secondUsageList = secondUsage.usageList;
      final int secondLen = secondUsageList.size();

      if (secondUsage.usageCount != secondLen || secondLen > 2) {
        return false;
      }

      final InputRefUsage<SqlOperator, RexNode> firstUsage =
          firstUsageMap.get(entry.getKey());

      if (firstUsage == null
          || firstUsage.usageList.size() != firstUsage.usageCount
          || firstUsage.usageCount > 2) {
        return false;
      }

      final List<Pair<SqlOperator, RexNode>> firstUsageList = firstUsage.usageList;
      final int firstLen = firstUsageList.size();

      final SqlKind fKind = firstUsageList.get(0).getKey().getKind();
      final SqlKind sKind = secondUsageList.get(0).getKey().getKind();
      final SqlKind fKind2 =
          (firstUsageList.size() == 2) ? firstUsageList.get(1).getKey().getKind() : null;
      final SqlKind sKind2 =
          (secondUsageList.size() == 2) ? secondUsageList.get(1).getKey().getKind() : null;

      if (firstLen == 2 && secondLen == 2
          && !(isEquivalentOp(fKind, sKind) && isEquivalentOp(fKind2, sKind2))
          && !(isEquivalentOp(fKind, sKind2) && isEquivalentOp(fKind2, sKind))) {
        return false;
      } else if (firstLen == 1 && secondLen == 1
          && fKind != SqlKind.EQUALS && !isEquivalentOp(fKind, sKind)) {
        return false;
      } else if (firstLen == 1 && secondLen == 2 && fKind != SqlKind.EQUALS) {
        return false;
      } else if (firstLen == 2 && secondLen == 1) {
        // Allow only cases like
        // x < 30 and x < 40 implies x < 70
        // x > 30 and x < 40 implies x < 70
        // But disallow cases like
        // x > 30 and x > 40 implies x < 70
        if (!isOppositeOp(fKind, fKind2)
            && !(isEquivalentOp(fKind, fKind2) && isEquivalentOp(fKind, sKind))) {
          return false;
        }
      }
    }

    return true;
  }

  private boolean isEquivalentOp(SqlKind fKind, SqlKind sKind) {
    switch (sKind) {
    case GREATER_THAN:
    case GREATER_THAN_OR_EQUAL:
      if (!(fKind == SqlKind.GREATER_THAN)
          && !(fKind == SqlKind.GREATER_THAN_OR_EQUAL)) {
        return false;
      }
      break;
    case LESS_THAN:
    case LESS_THAN_OR_EQUAL:
      if (!(fKind == SqlKind.LESS_THAN)
          && !(fKind == SqlKind.LESS_THAN_OR_EQUAL)) {
        return false;
      }
      break;
    default:
      return false;
    }

    return true;
  }

  private boolean isOppositeOp(SqlKind fKind, SqlKind sKind) {
    switch (sKind) {
    case GREATER_THAN:
    case GREATER_THAN_OR_EQUAL:
      if (!(fKind == SqlKind.LESS_THAN)
          && !(fKind == SqlKind.LESS_THAN_OR_EQUAL)) {
        return false;
      }
      break;
    case LESS_THAN:
    case LESS_THAN_OR_EQUAL:
      if (!(fKind == SqlKind.GREATER_THAN)
          && !(fKind == SqlKind.GREATER_THAN_OR_EQUAL)) {
        return false;
      }
      break;
    default:
      return false;
    }
    return true;
  }

  private boolean validate(RexNode first, RexNode second) {
    return first instanceof RexCall && second instanceof RexCall;
  }

  /**
   * Visitor that builds a usage map of inputs used by an expression.
   *
   * <p>E.g: for x > 10 AND y < 20 AND x = 40, usage map is as follows:
   * <ul>
   * <li>key: x value: {(>, 10),(=, 40), usageCount = 2}
   * <li>key: y value: {(>, 20), usageCount = 1}
   * </ul>
   */
  private static class InputUsageFinder extends RexVisitorImpl<Void> {
    public final Map<RexInputRef, InputRefUsage<SqlOperator, RexNode>>
    usageMap = new HashMap<>();

    public InputUsageFinder() {
      super(true);
    }

    public Void visitInputRef(RexInputRef inputRef) {
      InputRefUsage<SqlOperator, RexNode> inputRefUse = getUsageMap(inputRef);
      inputRefUse.usageCount++;
      return null;
    }

    @Override public Void visitCall(RexCall call) {
      switch (call.getOperator().getKind()) {
      case GREATER_THAN:
      case GREATER_THAN_OR_EQUAL:
      case LESS_THAN:
      case LESS_THAN_OR_EQUAL:
      case EQUALS:
      case NOT_EQUALS:
        updateUsage(call);
        break;
      default:
      }
      return super.visitCall(call);
    }

    private void updateUsage(RexCall call) {
      final List<RexNode> operands = call.getOperands();
      RexNode first = removeCast(operands.get(0));
      RexNode second = removeCast(operands.get(1));

      if (first.isA(SqlKind.INPUT_REF)
          && second.isA(SqlKind.LITERAL)) {
        updateUsage(call.getOperator(), (RexInputRef) first, second);
      }

      if (first.isA(SqlKind.LITERAL)
          && second.isA(SqlKind.INPUT_REF)) {
        updateUsage(reverse(call.getOperator()), (RexInputRef) second, first);
      }
    }

    private SqlOperator reverse(SqlOperator op) {
      return RelOptUtil.op(op.getKind().reverse(), op);
    }

    private static RexNode removeCast(RexNode inputRef) {
      if (inputRef instanceof RexCall) {
        final RexCall castedRef = (RexCall) inputRef;
        final SqlOperator operator = castedRef.getOperator();
        if (operator instanceof SqlCastFunction) {
          inputRef = castedRef.getOperands().get(0);
        }
      }
      return inputRef;
    }

    private void updateUsage(SqlOperator op, RexInputRef inputRef,
        RexNode literal) {
      final InputRefUsage<SqlOperator, RexNode> inputRefUse =
          getUsageMap(inputRef);
      Pair<SqlOperator, RexNode> use = Pair.of(op, literal);
      inputRefUse.usageList.add(use);
    }

    private InputRefUsage<SqlOperator, RexNode> getUsageMap(RexInputRef rex) {
      InputRefUsage<SqlOperator, RexNode> inputRefUse = usageMap.get(rex);
      if (inputRefUse == null) {
        inputRefUse = new InputRefUsage<>();
        usageMap.put(rex, inputRefUse);
      }

      return inputRefUse;
    }
  }

  /**
   * Usage of a {@link RexInputRef} in an expression.
   */
  private static class InputRefUsage<T1, T2> {
    private final List<Pair<T1, T2>> usageList = new ArrayList<>();
    private int usageCount = 0;
  }
}

// End RexImplicationChecker.java
