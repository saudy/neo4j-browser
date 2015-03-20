/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.planner.logical

import org.neo4j.cypher.internal.compiler.v2_3.ast.{HasLabels, Property, Collection}
import org.neo4j.cypher.internal.compiler.v2_3.commands.ManyQueryExpression
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.Metrics._
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans._

object CardinalityCostModel extends CostModel {

  /*
   * These constants are approximations derived from test runs,
   * see ActualCostCalculationTest
   */
  private val CPU_BOUND: CostPerRow = 0.1
  private val FAST_STORE: CostPerRow = 1.0
  private val SLOW_STORE: CostPerRow = 10.0
  private val PROBE_BUILD_COST = FAST_STORE
  private val PROBE_SEARCH_COST = PROBE_BUILD_COST * .5

  private def costPerRow(plan: LogicalPlan): CostPerRow = plan match {

    case _: Expand |
         _: VarExpand |
         _: AllNodesScan |
         _: DirectedRelationshipByIdSeek |
         _: UndirectedRelationshipByIdSeek |
         _: ProjectEndpoints |
         _: NodeByLabelScan
    => FAST_STORE

    // Filtering on labels and properties
    case Selection(predicates, _) if predicates.exists {
      case _: Property | _: HasLabels => true
      case _ => false
    }
    => FAST_STORE

    case _: NodeHashJoin |

         _: Aggregation |
         _: AbstractLetSemiApply |
         _: Limit |
         _: Optional |
         _: SingleRow |
         _: Argument |
         _: OuterHashJoin |
         _: AbstractSemiApply |
         _: Skip |
         _: Sort |
         _: SortedLimit |
         _: Union |
         _: Selection |
         _: UnwindCollection
    => CPU_BOUND

    case _: FindShortestPaths |
         _: LegacyIndexSeek |
         _: NodeByIdSeek |
         _: NodeIndexUniqueSeek |
         _: NodeIndexSeek
    => SLOW_STORE

    case NodeIndexSeek(_, _, _, ManyQueryExpression(Collection(elements)), _)
    => SLOW_STORE * Multiplier(elements.size)

    case _
    => CPU_BOUND
  }

  private def cardinalityForPlan(plan: LogicalPlan): Cardinality = plan match {
    case Selection(_, left) => left.solved.estimation
    case _ => plan.lhs.map(p => p.solved.estimation).getOrElse(plan.solved.estimation)
  }

  def apply(plan: LogicalPlan): Cost = plan match {
    case CartesianProduct(lhs, rhs) =>
      apply(lhs) + lhs.solved.estimation * apply(rhs)

    case ApplyVariants(lhs, rhs) =>
      val lCost = apply(lhs)
      val rCost = apply(rhs)

      // the rCost has already been multiplied by the lhs cardinality
      lCost + rCost

    case HashJoin(lhs, rhs) =>
      val lCost = apply(lhs)
      val rCost = apply(rhs)

      val lhsCardinality = lhs.solved.estimation
      val rhsCardinality = rhs.solved.estimation

      lCost + rCost +
        lhsCardinality * PROBE_BUILD_COST +
        rhsCardinality * PROBE_SEARCH_COST

    case _ =>
      val lhsCost = plan.lhs.map(p => apply(p)).getOrElse(Cost(0))
      val rhsCost = plan.rhs.map(p => apply(p)).getOrElse(Cost(0))
      val costForThisPlan = cardinalityForPlan(plan) * costPerRow(plan)
      val totalCost = costForThisPlan + lhsCost + rhsCost
      totalCost
  }

  object HashJoin {
    def unapply(x: Any): Option[(LogicalPlan, LogicalPlan)] = x match {
      case NodeHashJoin(_, l, r) => Some(l -> r)
      case OuterHashJoin(_, l, r) => Some(l -> r)
      case _ => None
    }
  }

  object ApplyVariants {
    def unapply(x: Any): Option[(LogicalPlan, LogicalPlan)] = x match {
      case Apply(l, r) => Some(l -> r)
      case p: AbstractLetSelectOrSemiApply => Some(p.lhs.get -> p.rhs.get)
      case p: AbstractSelectOrSemiApply => Some(p.lhs.get -> p.rhs.get)
      case p: AbstractSemiApply => Some(p.lhs.get -> p.rhs.get)
      case _ => None
    }
  }
}