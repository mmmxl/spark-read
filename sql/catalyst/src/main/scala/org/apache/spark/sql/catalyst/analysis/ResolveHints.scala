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

package org.apache.spark.sql.catalyst.analysis

import java.util.Locale

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.IntegerLiteral
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.CurrentOrigin
import org.apache.spark.sql.internal.SQLConf


/**
 * Collection of rules related to hints. The only hint currently available is broadcast join hint.
 *
 * Note that this is separately into two rules because in the future we might introduce new hint
 * rules that have different ordering requirements from broadcast.
 */
object ResolveHints {

  /**
   * For broadcast hint, we accept "BROADCAST", "BROADCASTJOIN", and "MAPJOIN", and a sequence of
   * relation aliases can be specified in the hint. A broadcast hint plan node will be inserted
   * on top of any relation (that is not aliased differently), subquery, or common table expression
   * that match the specified name.
   *
   * The hint resolution works by recursively traversing down the query plan to find a relation or
   * subquery that matches one of the specified broadcast aliases. The traversal does not go past
   * beyond any existing broadcast hints, subquery aliases.
   *
   * This rule must happen before common table expressions.
   */
  class ResolveBroadcastHints(conf: SQLConf) extends Rule[LogicalPlan] {
    // 这里可以看出BROADCAST JOIN和MAP JOIN是一摸一样的
    private val BROADCAST_HINT_NAMES = Set("BROADCAST", "BROADCASTJOIN", "MAPJOIN")

    def resolver: Resolver = conf.resolver

    private def applyBroadcastHint(plan: LogicalPlan, toBroadcast: Set[String]): LogicalPlan = {
      // Whether to continue recursing down the tree
      // 是否要继续往下递归
      var recurse = true

      val newNode = CurrentOrigin.withOrigin(plan.origin) {
        plan match {
          // 如果有广播hint的, 并且是未匹配catalog的表名，就返回新的类
          case u: UnresolvedRelation if toBroadcast.exists(resolver(_, u.tableIdentifier.table)) =>
            ResolvedHint(plan, HintInfo(broadcast = true))
          // 如果有广播hint的, 并且是子查询，就返回新的类
          case r: SubqueryAlias if toBroadcast.exists(resolver(_, r.alias)) =>
            ResolvedHint(plan, HintInfo(broadcast = true))
          // 如果是这些类，就结束迭代
          case _: ResolvedHint | _: View | _: With | _: SubqueryAlias =>
            // Don't traverse down these nodes.
            // For an existing broadcast hint, there is no point going down (if we do, we either
            // won't change the structure, or will introduce another broadcast hint that is useless.
            // The rest (view, with, subquery) indicates different scopes that we shouldn't traverse
            // down. Note that technically when this rule is executed, we haven't completed view
            // resolution yet and as a result the view part should be deadcode. I'm leaving it here
            // to be more future proof in case we change the view we do view resolution.
            // 不要向下遍历这些节点
            // 对于现有的广播提示，没有必要向下追溯（如果我们向下追溯，要么不会改变结构，要么会引入另一个无用的广播提示
            // 其余的（view、with、subquery）表示不同的范围，我们不应该向下遍历
            // 注意，技术上这个规则执行的时候，我们还没有完成视图解析，因此视图部分应该是死码
            // 我把它留在这里，是为了将来更有保障，以防我们改变我们做视图解析的视图。
            recurse = false
            plan

          case _ =>
            plan
        }
      }
      // 如果plan等于新结点，并且可以递归就继续递归，否则就返回
      if ((plan fastEquals newNode) && recurse) {
        newNode.mapChildren(child => applyBroadcastHint(child, toBroadcast))
      } else {
        newNode
      }
    }

    // 这里是一个偏函数, 如果不满足case就返回不做处理自身，否则执行匹配内的逻辑后返回
    def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperatorsUp {
      // 如果匹配到这个hint, 就执行
      case h: UnresolvedHint if BROADCAST_HINT_NAMES.contains(h.name.toUpperCase(Locale.ROOT)) =>
        if (h.parameters.isEmpty) {
          // If there is no table alias specified, turn the entire subtree into a BroadcastHint.
          // 如果没有指定表的别名，则将整个子树变成一个BroadcastHint
          ResolvedHint(h.child, HintInfo(broadcast = true))
        } else {
          // Otherwise, find within the subtree query plans that should be broadcasted.
          // 否则，在子树查询计划中找到应该播报的计划
          applyBroadcastHint(h.child, h.parameters.map {
            case tableName: String => tableName
            case tableId: UnresolvedAttribute => tableId.name
            case unsupported => throw new AnalysisException("Broadcast hint parameter should be " +
              s"an identifier or string but was $unsupported (${unsupported.getClass}")
          }.toSet)
        }
    }
  }

  /**
   * COALESCE Hint accepts name "COALESCE" and "REPARTITION".
   * Its parameter includes a partition number.
   */
  object ResolveCoalesceHints extends Rule[LogicalPlan] {
    private val COALESCE_HINT_NAMES = Set("COALESCE", "REPARTITION")

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperators {
      case h: UnresolvedHint if COALESCE_HINT_NAMES.contains(h.name.toUpperCase(Locale.ROOT)) =>
        val hintName = h.name.toUpperCase(Locale.ROOT)
        val shuffle = hintName match {
          case "REPARTITION" => true
          case "COALESCE" => false
        }
        val numPartitions = h.parameters match {
          case Seq(IntegerLiteral(numPartitions)) =>
            numPartitions
          case Seq(numPartitions: Int) =>
            numPartitions
          case _ =>
            throw new AnalysisException(s"$hintName Hint expects a partition number as parameter")
        }
        // 返回一个逻辑树的子类Repartition
        Repartition(numPartitions, shuffle, h.child)
    }
  }

  /**
   * Removes all the hints, used to remove invalid hints provided by the user.
   * This must be executed after all the other hint rules are executed.
   * 删除所有的提示，用于删除用户提供的无效提示。此项必须在所有其他提示规则被执行后执行。
   */
  object RemoveAllHints extends Rule[LogicalPlan] {
    // 删除就是返回子结点
    def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperatorsUp {
      case h: UnresolvedHint => h.child
    }
  }

}
