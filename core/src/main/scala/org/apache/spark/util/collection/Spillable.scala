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

package org.apache.spark.util.collection

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.memory.{MemoryConsumer, MemoryMode, TaskMemoryManager}

/**
 * Spills contents of an in-memory collection to disk when the memory threshold
 * has been exceeded.
 * 当超过内存阈值时，将内存中集合的内容溢出到磁盘
 */
private[spark] abstract class Spillable[C](taskMemoryManager: TaskMemoryManager)
  extends MemoryConsumer(taskMemoryManager) with Logging {
  /**
   * Spills the current in-memory collection to disk, and releases the memory.
   * 溢写当前内存中的集合到磁盘,并且释放内存
   * @param collection collection to spill to disk
   */
  protected def spill(collection: C): Unit

  /**
   * Force to spilling the current in-memory collection to disk to release memory,
   * It will be called by TaskMemoryManager when there is not enough memory for the task.
   * 强制溢写当前内存中的集合到磁盘,并且释放内存
   */
  protected def forceSpill(): Boolean

  // Number of elements read from input since last spill
  // 自上次溢出后从输入中读取的元素数量
  protected def elementsRead: Int = _elementsRead

  // Called by subclasses every time a record is read
  // It's used for checking spilling frequency
  // 每次读取记录时由子类调用，用于检查溢出频率。
  protected def addElementsRead(): Unit = { _elementsRead += 1 }

  // Initial threshold for the size of a collection before we start tracking its memory usage
  // For testing only
  // 初始的内存阈值 默认5m
  private[this] val initialMemoryThreshold: Long =
    SparkEnv.get.conf.getLong("spark.shuffle.spill.initialMemoryThreshold", 5 * 1024 * 1024)

  // Force this collection to spill when there are this many elements in memory
  // For testing only
  // spark.shuffle.spill.numElementsForceSpillThreshold
  // 上一次溢写后读入元素数量溢写的阈值
  private[this] val numElementsForceSpillThreshold: Int =
    SparkEnv.get.conf.get(SHUFFLE_SPILL_NUM_ELEMENTS_FORCE_SPILL_THRESHOLD)

  // Threshold for this collection's size in bytes before we start tracking its memory usage
  // To avoid a large number of small spills, initialize this to a value orders of magnitude > 0
  // 在我们开始跟踪内存使用情况之前，这个集合大小的阈值（以字节为单位）。
  // 为了避免大量的小泄漏，将其初始化为一个数量级大于0的值。
  @volatile private[this] var myMemoryThreshold = initialMemoryThreshold

  // Number of elements read from input since last spill
  private[this] var _elementsRead = 0

  // Number of bytes spilled in total
  // 溢写的数据量大小
  @volatile private[this] var _memoryBytesSpilled = 0L

  // Number of spills
  // 溢写的次数
  private[this] var _spillCount = 0

  /**
   * Spills the current in-memory collection to disk if needed. Attempts to acquire more
   * memory before spilling.
   * 如果需要，将当前内存中的集合溢出到磁盘。在溢出之前，尝试获取更多的内存。
   *
   * 1.根据每次集合读取到内存中的数据条数和当前读取的内存检查内存是否需要溢写磁盘(每添加32个元素判断一次)
   *   currentMemory >= myMemoryThreshold
   *   1): currentMemory:6m myMemoryThreshold:5m => amountToRequest = 6m*2 - 5m = 7m
   *   granted=7m myMemoryThreshold = 12m 不溢写
   *   2): currentMemory:13m myMemoryThreshold:20m => amountToRequest = 20m*2 - 12m = 28m
   *   granted=4m myMemoryThreshold = 16m 溢写
   *
   * 2.如果读取元素数量大于numElementsForceSpillThreshold也溢写
   * 3.如果溢写,
   *   3.1 溢写数+1
   *   3.2 log
   *   3.3 溢写(需要子类实现)
   *   3.4 读取元素数归零
   *   3.5 溢写数据量增加
   *   3.6 释放内存
   *
   * @param collection collection to spill to disk
   * @param currentMemory estimated size of the collection in bytes
   * @return true if `collection` was spilled to disk; false otherwise
   */
  protected def maybeSpill(collection: C, currentMemory: Long): Boolean = {
    var shouldSpill = false
    // 根据每次集合读取到内存中的数据条数和当前读取的内存检查内存是否需要溢写磁盘
    // 每添加32个元素判断一次
    if (elementsRead % 32 == 0 && currentMemory >= myMemoryThreshold) {
      // Claim up to double our current memory from the shuffle memory pool
      // 从shuffle内存池中申请最多双倍的当前内存。
      val amountToRequest = 2 * currentMemory - myMemoryThreshold
      // 获取内存,acquireMemory有释放内存(调用spill())的可能
      val granted = acquireMemory(amountToRequest)
      myMemoryThreshold += granted
      // If we were granted too little memory to grow further (either tryToAcquire returned 0,
      // or we already had more memory than myMemoryThreshold), spill the current collection
      // 如果我们获得的内存太少，无法进一步增长
      // （tryToAcquire返回0或者我们的内存已经超过了myMemoryThreshold)，溢出当前的集合。
      shouldSpill = currentMemory >= myMemoryThreshold
    }
    // 如果读取元素数量大于numElementsForceSpillThreshold也溢写
    shouldSpill = shouldSpill || _elementsRead > numElementsForceSpillThreshold
    // Actually spill
    if (shouldSpill) {
      _spillCount += 1
      logSpillage(currentMemory)
      spill(collection)
      _elementsRead = 0
      _memoryBytesSpilled += currentMemory
      releaseMemory()
    }
    shouldSpill
  }

  /**
   * Spill some data to disk to release memory, which will be called by TaskMemoryManager
   * when there is not enough memory for the task.
   */
  override def spill(size: Long, trigger: MemoryConsumer): Long = {
    if (trigger != this && taskMemoryManager.getTungstenMemoryMode == MemoryMode.ON_HEAP) {
      val isSpilled = forceSpill()
      if (!isSpilled) {
        0L
      } else {
        val freeMemory = myMemoryThreshold - initialMemoryThreshold
        _memoryBytesSpilled += freeMemory
        releaseMemory()
        freeMemory
      }
    } else {
      0L
    }
  }

  /**
   * @return number of bytes spilled in total
   */
  def memoryBytesSpilled: Long = _memoryBytesSpilled

  /**
   * Release our memory back to the execution pool so that other tasks can grab it.
   */
  def releaseMemory(): Unit = {
    freeMemory(myMemoryThreshold - initialMemoryThreshold)
    myMemoryThreshold = initialMemoryThreshold
  }

  /**
   * Prints a standard log message detailing spillage.
   *
   * @param size number of bytes spilled
   */
  @inline private def logSpillage(size: Long) {
    val threadId = Thread.currentThread().getId
    logInfo("Thread %d spilling in-memory map of %s to disk (%d time%s so far)"
      .format(threadId, org.apache.spark.util.Utils.bytesToString(size),
        _spillCount, if (_spillCount > 1) "s" else ""))
  }
}
