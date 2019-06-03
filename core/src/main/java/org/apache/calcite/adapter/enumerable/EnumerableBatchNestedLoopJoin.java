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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.function.Predicate2;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.DeclarationStatement;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Implementation of {@link org.apache.calcite.rel.core.Join} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
public class EnumerableBatchNestedLoopJoin extends Join implements EnumerableRel {

  private final ImmutableBitSet requiredColumns;
  protected EnumerableBatchNestedLoopJoin(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode left,
      RelNode right,
      RexNode condition,
      Set<CorrelationId> variablesSet,
      ImmutableBitSet requiredColumns,
      JoinRelType joinType) {
    super(cluster, traits, left, right, condition, variablesSet, joinType);
    this.requiredColumns = requiredColumns;
  }

  public static EnumerableBatchNestedLoopJoin create(
      RelNode left,
      RelNode right,
      RexNode condition,
      ImmutableBitSet requiredColumns,
      Set<CorrelationId> variablesSet,
      JoinRelType joinType) {
    final RelOptCluster cluster = left.getCluster();
    final RelMetadataQuery mq = cluster.getMetadataQuery();
    final RelTraitSet traitSet =
        cluster.traitSetOf(EnumerableConvention.INSTANCE)
            .replaceIfs(RelCollationTraitDef.INSTANCE,
                () -> RelMdCollation.enumerableBatchNestedLoopJoin(mq, left, right, joinType));
    return new EnumerableBatchNestedLoopJoin(
        cluster,
        traitSet,
        left,
        right,
        condition,
        variablesSet,
        requiredColumns,
        joinType);
  }

  @Override public EnumerableBatchNestedLoopJoin copy(RelTraitSet traitSet,
      RexNode condition, RelNode left, RelNode right, JoinRelType joinType,
      boolean semiJoinDone) {
    return new EnumerableBatchNestedLoopJoin(getCluster(), traitSet,
        left, right, condition, variablesSet, requiredColumns, joinType);
  }

  @Override public RelOptCost computeSelfCost(
      final RelOptPlanner planner,
      final RelMetadataQuery mq) {
    double rowCount = mq.getRowCount(this);

    final double rightRowCount = right.estimateRowCount(mq);
    final double leftRowCount = left.estimateRowCount(mq);
    if (Double.isInfinite(leftRowCount) || Double.isInfinite(rightRowCount)) {
      return planner.getCostFactory().makeInfiniteCost();
    }

    Double restartCount = mq.getRowCount(getLeft()) / variablesSet.size();

    RelOptCost rightCost = planner.getCost(getRight(), mq);
    RelOptCost rescanCost =
        rightCost.multiplyBy(Math.max(1.0, restartCount - 1));

    // TODO Add cost of last loop (the one that looks for the match)
    return planner.getCostFactory().makeCost(
        rowCount + leftRowCount, 0, 0).plus(rescanCost);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    return pw.item("batchSize", variablesSet.size());
  }

  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final BlockBuilder builder = new BlockBuilder();
    final Result leftResult =
        implementor.visitChild(this, 0, (EnumerableRel) left, pref);
    Expression leftExpression =
        builder.append(
            "left", leftResult.block);

    List<String> corrVar = new ArrayList<>();
    for (CorrelationId c : variablesSet) {
      corrVar.add(c.getName());
    }

    final BlockBuilder corrBlock = new BlockBuilder();
    Type corrVarType = leftResult.physType.getJavaRowType();
    ParameterExpression corrArg;
    ParameterExpression corrArgList =
        Expressions.parameter(Modifier.FINAL,
            List.class, "corrList");

    // Declare batchSize correlation variables
    if (!Primitive.is(corrVarType)) {
      for (int c = 0; c < corrVar.size(); c++) {
        corrArg =
            Expressions.parameter(Modifier.FINAL,
                corrVarType, corrVar.get(c));
        DeclarationStatement decl = Expressions.declare(
            Modifier.FINAL,
            corrArg,
            Expressions.convert_(
                Expressions.call(
                    corrArgList,
                    BuiltInMethod.LIST_GET.method,
                    Expressions.constant(c)),
                corrVarType));
        corrBlock.add(decl);
        implementor.registerCorrelVariable(corrVar.get(c), corrArg,
            corrBlock, leftResult.physType);
      }
    } else {
      for (int c = 0; c < corrVar.size(); c++) {
        corrArg =
            Expressions.parameter(Modifier.FINAL,
                Primitive.box(corrVarType), "$box" + corrVar.get(c));
        DeclarationStatement decl = Expressions.declare(
            Modifier.FINAL,
            corrArg,
            Expressions.call(
                corrArgList,
                BuiltInMethod.LIST_GET.method,
                Expressions.constant(c)));
        corrBlock.add(decl);
        ParameterExpression corrRef =
            (ParameterExpression) corrBlock.append(corrVar.get(c),
                Expressions.unbox(corrArg));
        implementor.registerCorrelVariable(corrVar.get(c), corrRef,
            corrBlock, leftResult.physType);
      }
    }
    final Result rightResult =
        implementor.visitChild(this, 1, (EnumerableRel) right, pref);

    corrBlock.add(rightResult.block);
    for (String c : corrVar) {
      implementor.clearCorrelVariable(c);
    }

    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(),
            getRowType(),
            pref.prefer(JavaRowFormat.CUSTOM));
    Expression selector =
        EnumUtils.joinSelector(
            joinType, physType,
            ImmutableList.of(leftResult.physType, rightResult.physType));

    final BlockBuilder builderPredicate = new BlockBuilder();
    Expression predicate = predicate(implementor,
        builderPredicate,
        leftResult.physType,
        rightResult.physType);

    builder.append(
        Expressions.call(BuiltInMethod.CORRELATE_BATCH_JOIN.method,
            Expressions.constant(EnumUtils.toLinq4jJoinType(joinType)),
            leftExpression,
            Expressions.lambda(corrBlock.toBlock(), corrArgList),
            selector,
            predicate,
            Expressions.constant(variablesSet.size())));
    return implementor.result(physType, builder.toBlock());
  }

  // Predicate "matches" that is gonna be used in correlateBatchJoin to compare two tuples
  Expression predicate(EnumerableRelImplementor implementor,
      BlockBuilder builder, PhysType leftPhysType, PhysType rightPhysType) {
    final ParameterExpression left_ =
        Expressions.parameter(leftPhysType.getJavaRowType(), "left");
    final ParameterExpression right_ =
        Expressions.parameter(rightPhysType.getJavaRowType(), "right");
    final RexProgramBuilder program =
        new RexProgramBuilder(
            implementor.getTypeFactory().builder()
                .addAll(left.getRowType().getFieldList())
                .addAll(right.getRowType().getFieldList())
                .build(),
            getCluster().getRexBuilder());
    program.addCondition(condition);
    builder.add(
        Expressions.return_(null,
            RexToLixTranslator.translateCondition(program.getProgram(),
                implementor.getTypeFactory(),
                builder,
                new RexToLixTranslator.InputGetterImpl(
                    ImmutableList.of(Pair.of(left_, leftPhysType),
                        Pair.of(right_, rightPhysType))),
                implementor.allCorrelateVariables,
                implementor.getConformance())));
    return Expressions.lambda(Predicate2.class, builder.toBlock(), left_,
        right_);
  }
}

// End EnumerableBatchNestedLoopJoin.java
