/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.{CandidateList, LogicalPlanContext, LeafPlanner}
import org.neo4j.cypher.internal.compiler.v2_1.planner.QueryGraph

object labelScanLeafPlanner extends LeafPlanner {
  def apply(qg: QueryGraph)(implicit context: LogicalPlanContext) = {
    implicit val semanticTable = context.semanticTable
    val labelPredicateMap = qg.selections.labelPredicates

    CandidateList(
      for (idName <- qg.patternNodes.toSeq;
           labelPredicate <- labelPredicateMap.getOrElse(idName, Set.empty);
           labelName <- labelPredicate.labels) yield
        NodeByLabelScan(idName, labelName.either)(Seq(labelPredicate))
    )
  }
}
