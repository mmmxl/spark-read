/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.Attribute

/**
 * A general hint for the child that is not yet resolved. This node is generated by the parser and
 * should be removed This node will be eliminated post analysis.
 *
 * 一个尚未解析的子节点的一般提示。这个节点是由解析器生成的，这个节点将在分析后被删除。
 * @param name the name of the hint
 * @param parameters the parameters of the hint
 * @param child the [[LogicalPlan]] on which this hint applies
 */
case class UnresolvedHint(name: String, parameters: Seq[Any], child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = false
  override def output: Seq[Attribute] = child.output
}

/**
 * A resolved hint node. The analyzer should convert all [[UnresolvedHint]] into [[ResolvedHint]].
 * 一个已经被分析过的hint结点,分析器应该转换所有 UnresolvedHint 变成 ResolvedHint
 */
case class ResolvedHint(child: LogicalPlan, hints: HintInfo = HintInfo())
  extends UnaryNode {

  override def output: Seq[Attribute] = child.output

  override def doCanonicalize(): LogicalPlan = child.canonicalized
}


case class HintInfo(broadcast: Boolean = false) {

  /** Must be called when computing stats for a join operator to reset hints.
   * 当计算join操作符的状态，必须调用它来重置hints
   */
  def resetForJoin(): HintInfo = copy(broadcast = false)

  override def toString: String = {
    val hints = scala.collection.mutable.ArrayBuffer.empty[String]
    if (broadcast) {
      hints += "broadcast"
    }

    if (hints.isEmpty) "none" else hints.mkString("(", ", ", ")")
  }
}
