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

package org.apache.spark.sql.catalyst.rules

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.catalyst.util.sideBySide
import org.apache.spark.util.Utils

object RuleExecutor {
  // rule执行时间和数量的监控器
  protected val queryExecutionMeter = QueryExecutionMetering()

  /** Dump statistics about time spent running specific rules.
   * rule的统计信息(执行时间和数量的信息)
   */
  def dumpTimeSpent(): String = {
    queryExecutionMeter.dumpTimeSpent()
  }

  /** Resets statistics about time spent running specific rules
   * 清空rule的统计信息
   */
  def resetMetrics(): Unit = {
    queryExecutionMeter.resetMetrics()
  }
}

abstract class RuleExecutor[TreeType <: TreeNode[_]] extends Logging {

  /**
   * An execution strategy for rules that indicates the maximum number of executions. If the
   * execution reaches fix point (i.e. converge) before maxIterations, it will stop.
   */
  abstract class Strategy { def maxIterations: Int }

  /** A strategy that only runs once. */
  case object Once extends Strategy { val maxIterations = 1 }

  /**
   * A strategy that runs until fix point or maxIterations times, whichever comes first.
   * 一直运行到固定点或 maxIterations 次的策略，以先到者为准。
   * 固定点：即经过一次迭代后，plan未发生改变
   */
  case class FixedPoint(maxIterations: Int) extends Strategy

  /** A batch of rules. */
  protected case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)

  /** Defines a sequence of rule batches, to be overridden by the implementation. */
  protected def batches: Seq[Batch]

  /**
   * Defines a check function that checks for structural integrity of the plan after the execution
   * of each rule. For example, we can check whether a plan is still resolved after each rule in
   * `Optimizer`, so we can catch rules that return invalid plans. The check function returns
   * `false` if the given plan doesn't pass the structural integrity check.
   * 对plan对结构完整性检查 e.g.一个物理树是否还在被解析
   */
  protected def isPlanIntegral(plan: TreeType): Boolean = true

  /**
   * Executes the batches of rules defined by the subclass. The batches are executed serially
   * using the defined execution strategy. Within each batch, rules are also executed serially.
   * 执行子类定义的规则批次。批次使用定义的执行策略连续执行。在每个批次中，规则也是串行执行的。
   *
   * 执行过程（忽略监控操作）
   * 1.遍历batches
   *   1.1.遍历batch
   *       > 新建一个rule的TreeType对象(plan也是这个类型的)，就是树结点的转换
   *       > 在每条规则之后，对照计划运行结构完整性检查器
   *   1.2.迭代次数+1
   *   1.3.检查迭代次数是否达到策略最大次数，达到就结束遍历
   *   1.4.检查当前结束的plan是否等于固定点，相等就结束遍历
   * 2.如果plan发生改变,记录log debug
   * 3.返回plan
   */
  def execute(plan: TreeType): TreeType = {
    var curPlan = plan
    // 监控
    val queryExecutionMetrics = RuleExecutor.queryExecutionMeter

    batches.foreach { batch =>
      // 开始的Plan
      val batchStartPlan = curPlan
      var iteration = 1 // 表示开始第几次迭代
      var lastPlan = curPlan
      var continue = true

      // Run until fix point (or the max number of iterations as specified in the strategy.
      // 运行到固定点（或策略中指定的最大迭代次数）
      while (continue) {
        // 进行一批rule转换
        curPlan = batch.rules.foldLeft(curPlan) {
          case (plan, rule) =>
            val startTime = System.nanoTime()
            val result = rule(plan)
            val runTime = System.nanoTime() - startTime

            if (!result.fastEquals(plan)) {
              // 规则转换后前后plan不一致
              // 监控系统 增加让plan发生改变的指定规则使用次数
              queryExecutionMetrics.incNumEffectiveExecution(rule.ruleName)
              // 监控系统 增加让plan发生改变的指定规则的总耗时
              queryExecutionMetrics.incTimeEffectiveExecutionBy(rule.ruleName, runTime)
              logTrace(
                s"""
                  |=== Applying Rule ${rule.ruleName} ===
                  |${sideBySide(plan.treeString, result.treeString).mkString("\n")}
                """.stripMargin)
            }
            // 监控系统 增加指定规则的总耗时
            queryExecutionMetrics.incExecutionTimeBy(rule.ruleName, runTime)
            // 监控系统 增加指定规则使用次数
            queryExecutionMetrics.incNumExecution(rule.ruleName)

            // Run the structural integrity checker against the plan after each rule.
            // 在每条规则之后，对照计划运行结构完整性检查器。
            if (!isPlanIntegral(result)) {
              val message = s"After applying rule ${rule.ruleName} in batch ${batch.name}, " +
                "the structural integrity of the plan is broken."
              throw new TreeNodeException(result, message, null)
            }

            result
        }
        // +1, 表示当前迭代结束, 开始下一次迭代
        iteration += 1
        // 如果已经迭代次数等于策略的次数，相同就结束迭代
        if (iteration > batch.strategy.maxIterations) {
          // Only log if this is a rule that is supposed to run more than once.
          if (iteration != 2) {
            // 已经打到最大迭代次数,上面迭代次数已经+1,所以这里需要-1
            val message = s"Max iterations (${iteration - 1}) reached for batch ${batch.name}"
            if (Utils.isTesting) {
              // 测试从这里结束
              throw new TreeNodeException(curPlan, message, null)
            } else {
              logWarning(message)
            }
          }
          continue = false
        }
        // 如果迭代一次之后等于开始的plan，就结束了
        if (curPlan.fastEquals(lastPlan)) {
          logTrace(
            s"Fixed point reached for batch ${batch.name} after ${iteration - 1} iterations.")
          continue = false
        }
        lastPlan = curPlan
      }
      // 如果plan发生改变,走log debug
      if (!batchStartPlan.fastEquals(curPlan)) {
        logDebug(
          s"""
            |=== Result of Batch ${batch.name} ===
            |${sideBySide(batchStartPlan.treeString, curPlan.treeString).mkString("\n")}
          """.stripMargin)
      } else {
        logTrace(s"Batch ${batch.name} has no effect.")
      }
    }
    // 返回执行rule之后的plan
    curPlan
  }
}
