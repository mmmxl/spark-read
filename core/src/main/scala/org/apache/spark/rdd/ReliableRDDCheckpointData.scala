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

import org.apache.hadoop.fs.Path

import org.apache.spark._
import org.apache.spark.internal.Logging

/**
 * An implementation of checkpointing that writes the RDD data to reliable storage.
 * This allows drivers to be restarted on failure with previously computed state.
 * 检查点的实现，将RDD数据写入可靠的存储。
 * 这允许驱动程序在故障时以先前计算的状态重新启动。
 */
private[spark] class ReliableRDDCheckpointData[T: ClassTag](@transient private val rdd: RDD[T])
  extends RDDCheckpointData[T](rdd) with Logging {

  // The directory to which the associated RDD has been checkpointed to
  // This is assumed to be a non-local path that points to some reliable storage
  // 获取该rdd在非本地存储上的 checkpoint 路径
  // 设置方法: spark.sparkContext.setCheckpointDir("/user/tmp/") 这里为 /user/tmp/rdd-${rddId}
  // 通常为hdfs地址
  private val cpDir: String =
    ReliableRDDCheckpointData.checkpointPath(rdd.context, rdd.id)
      .map(_.toString)
      .getOrElse { throw new SparkException("Checkpoint dir must be specified.") }

  /**
   * Return the directory to which this RDD was checkpointed.
   * If the RDD is not checkpointed yet, return None.
   * 拿到checkpoint目录，未持久化成功，返回None
   */
  def getCheckpointDir: Option[String] = RDDCheckpointData.synchronized {
    if (isCheckpointed) {
      Some(cpDir.toString)
    } else {
      None
    }
  }

  /**
   * Materialize this RDD and write its content to a reliable DFS.
   * This is called immediately after the first action invoked on this RDD has completed.
   * 将该 RDD 具体化，并将其内容持久化。
   * 在该 RDD 上调用的第一个action算子完成后，会立即调用该功能。
   *
   * 1.返回checkpoint目录的rdd
   * 2.检查引用超出了范围，可以选择清理我们的检查点文件
   * 3.打印日志,返回rdd
   *
   */
  protected override def doCheckpoint(): CheckpointRDD[T] = {
    val newRDD = ReliableCheckpointRDD.writeRDDToCheckpointDirectory(rdd, cpDir)

    // Optionally clean our checkpoint files if the reference is out of scope
    // 如果引用超出了范围，可以选择清理我们的检查点文件
    if (rdd.conf.getBoolean("spark.cleaner.referenceTracking.cleanCheckpoints", false)) {
      rdd.context.cleaner.foreach { cleaner =>
        cleaner.registerRDDCheckpointDataForCleanup(newRDD, rdd.id)
      }
    }

    logInfo(s"Done checkpointing RDD ${rdd.id} to $cpDir, new parent is RDD ${newRDD.id}")
    newRDD
  }

}

/**
 * 这里提供了rdd获得自己checkpoint目录和清理目录的2个方法
 */
private[spark] object ReliableRDDCheckpointData extends Logging {

  /**
   * Return the path of the directory to which this RDD's checkpoint data is written.
   * 返回rdd的checkpoint目录
   * e.g. spark.sparkContext.setCheckpointDir("/user/tmp/"), rddId为1
   *      这里返回/user/tmp//rdd-1
   */
  def checkpointPath(sc: SparkContext, rddId: Int): Option[Path] = {
    sc.checkpointDir.map { dir => new Path(dir, s"rdd-$rddId") }
  }

  /**
   * Clean up the files associated with the checkpoint data for this RDD.
   * 删除这个rdd的checkpoint目录
   */
  def cleanCheckpoint(sc: SparkContext, rddId: Int): Unit = {
    checkpointPath(sc, rddId).foreach { path =>
      path.getFileSystem(sc.hadoopConfiguration).delete(path, true)
    }
  }
}
