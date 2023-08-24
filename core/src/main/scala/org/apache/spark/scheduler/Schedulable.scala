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

package org.apache.spark.scheduler

import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.scheduler.SchedulingMode.SchedulingMode

/**
 * An interface for schedulable entities.
 * there are two type of Schedulable entities(Pools and TaskSetManagers)
 */
private[spark] trait Schedulable {
  var parent: Pool // 当前Pool的父Pool
  // child queues
  def schedulableQueue: ConcurrentLinkedQueue[Schedulable]
  def schedulingMode: SchedulingMode
  def weight: Int // 公平调度的权重
  def minShare: Int // 公平调度的参考值
  def runningTasks: Int // 当前正在运行的任务数量
  def priority: Int // 进行调度的优先级
  def stageId: Int
  def name: String

  def addSchedulable(schedulable: Schedulable): Unit
  def removeSchedulable(schedulable: Schedulable): Unit
  def getSchedulableByName(name: String): Schedulable

  /** 用于当某个Executor丢失后，将在此Executor上执行的Task作为失败任务处理，并重新提交这些任务 */
  def executorLost(executorId: String, host: String, reason: ExecutorLossReason): Unit
  /** 用于检查当前Pool中是否有需要推测执行的任务 */
  def checkSpeculatableTasks(minTimeToSpeculation: Int): Boolean
  def getSortedTaskSetQueue: ArrayBuffer[TaskSetManager]
}
