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

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.storage.{RDDBlockId, StorageLevel}
import org.apache.spark.util.Utils

/**
 * An implementation of checkpointing implemented on top of Spark's caching layer.
 *
 * Local checkpointing trades off fault tolerance for performance by skipping the expensive
 * step of saving the RDD data to a reliable and fault-tolerant storage. Instead, the data
 * is written to the local, ephemeral block storage that lives in each executor. This is useful
 * for use cases where RDDs build up long lineages that need to be truncated often (e.g. GraphX).
 * 在Spark的缓存层之上实现了检查点。
 * 本地检查点通过跳过将RDD数据保存到可靠的容错存储这一昂贵步骤，来换取性能。
 * 取而代之的是，数据被写入住在每个执行器中的本地、短暂的块存储。
 * 这对于RDD建立需要经常截断的冗长血缘的使用案例来说非常有用（例如GraphX）。
 *
 * 用本地的block来取代稳定容错的存储来做checkpoint
 * e.g. Yarn模式下,用persist的block level来取代hdfs上的checkpoint文件
 */
private[spark] class LocalRDDCheckpointData[T: ClassTag](@transient private val rdd: RDD[T])
  extends RDDCheckpointData[T](rdd) with Logging {

  /**
   * Ensure the RDD is fully cached so the partitions can be recovered later.
   * 1.检查StorageLevel是否包含Disk
   * 2.计算出rdd有哪些分区没生成block
   * 3.如果有分区未生成block,就执行rdd的这些分区
   * 4.创建LocalCheckpointRDD
   */
  protected override def doCheckpoint(): CheckpointRDD[T] = {
    val level = rdd.getStorageLevel

    // Assume storage level uses disk; otherwise memory eviction may cause data loss
    // 断言保证存储层使用磁盘，否则内存疏散(存储内存不足时)可能导致数据丢失
    // appropriate 恰当的
    assume(level.useDisk, s"Storage level $level is not appropriate for local checkpointing")

    // Not all actions compute all partitions of the RDD (e.g. take). For correctness, we
    // must cache any missing partitions. TODO: avoid running another job here (SPARK-8582).
    val action = (tc: TaskContext, iterator: Iterator[T]) => Utils.getIteratorSize(iterator)
    val missingPartitionIndices = rdd.partitions.map(_.index).filter { i =>
      !SparkEnv.get.blockManager.master.contains(RDDBlockId(rdd.id, i))
    }
    if (missingPartitionIndices.nonEmpty) {
      rdd.sparkContext.runJob(rdd, action, missingPartitionIndices)
    }

    new LocalCheckpointRDD[T](rdd)
  }

}

private[spark] object LocalRDDCheckpointData {

  val DEFAULT_STORAGE_LEVEL = StorageLevel.MEMORY_AND_DISK

  /**
   * Transform the specified storage level to one that uses disk.
   *
   * This guarantees that the RDD can be recomputed multiple times correctly as long as
   * executors do not fail. Otherwise, if the RDD is cached in memory only, for instance,
   * the checkpoint data will be lost if the relevant block is evicted from memory.
   *
   * This method is idempotent.
   */
  def transformStorageLevel(level: StorageLevel): StorageLevel = {
    StorageLevel(useDisk = true, level.useMemory, level.deserialized, level.replication)
  }
}
