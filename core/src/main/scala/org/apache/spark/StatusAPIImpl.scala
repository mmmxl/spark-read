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

package org.apache.spark

private class SparkJobInfoImpl (
    val jobId: Int,
    val stageIds: Array[Int],
    val status: JobExecutionStatus)
  extends SparkJobInfo

private class SparkStageInfoImpl(
    val stageId: Int,
    val currentAttemptId: Int, // 当前尝试的id
    val submissionTime: Long, // 提交时间戳
    val name: String, // 名字
    val numTasks: Int, // 任务数
    val numActiveTasks: Int, // 活跃任务数
    val numCompletedTasks: Int, // 结束任务数
    val numFailedTasks: Int /* 失败任务数 */)
  extends SparkStageInfo

private class SparkExecutorInfoImpl(
    val host: String,
    val port: Int,
    val cacheSize: Long, // cache大小
    val numRunningTasks: Int, // 运行中的任务数
    val usedOnHeapStorageMemory: Long, // 被使用的堆内存储内存大小
    val usedOffHeapStorageMemory: Long, // 被使用的堆外存储内存大小
    val totalOnHeapStorageMemory: Long, // 堆内存储内存总和
    val totalOffHeapStorageMemory: Long /* 堆外存储内存总和 */ )
  extends SparkExecutorInfo
