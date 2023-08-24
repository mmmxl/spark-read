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

package org.apache.spark.memory

import javax.annotation.concurrent.GuardedBy

import org.apache.spark.internal.Logging
import org.apache.spark.storage.BlockId
import org.apache.spark.storage.memory.MemoryStore

/**
 * Performs bookkeeping for managing an adjustable-size pool of memory that is used for storage
 * (caching)
 * 对于存储的物理内存的逻辑抽象，通过对存储内存的逻辑管理，提高Spark存储体系对内存的使用效率
 *
 * @param lock a [[MemoryManager]] instance to synchronize on
 * @param memoryMode the type of memory tracked by this pool (on- or off-heap)
 */
private[memory] class StorageMemoryPool(
    lock: Object,
    memoryMode: MemoryMode // 内存模式
  ) extends MemoryPool(lock) with Logging {

  // 内存池的名字
  private[this] val poolName: String = memoryMode match {
    case MemoryMode.ON_HEAP => "on-heap storage"
    case MemoryMode.OFF_HEAP => "off-heap storage"
  }

  // 已经使用的内存大小(单位：字节)
  @GuardedBy("lock")
  private[this] var _memoryUsed: Long = 0L

  override def memoryUsed: Long = lock.synchronized {
    _memoryUsed
  }

  // 当前StorageMemoryPool所关联的MemoryStore
  private var _memoryStore: MemoryStore = _
  def memoryStore: MemoryStore = {
    if (_memoryStore == null) {
      throw new IllegalStateException("memory store not initialized yet")
    }
    _memoryStore
  }

  /**
   * Set the [[MemoryStore]] used by this manager to evict cached blocks.
   * This must be set after construction due to initialization ordering constraints.
   * 设置[[MemoryStore]]，该管理器用于驱逐缓存块。
   * 由于初始化顺序限制，必须在构造后设置。
   */
  final def setMemoryStore(store: MemoryStore): Unit = {
    _memoryStore = store
  }

  /**
   * Acquire N bytes of memory to cache the given block, evicting existing ones if necessary.
   * 给BlockId对应的Block获取numBytes指定大小的内存，必要时驱逐现有的块。
   * @return whether all N bytes were successfully granted.
   */
  def acquireMemory(blockId: BlockId, numBytes: Long): Boolean = lock.synchronized {
    // 计算申请内存大小与空闲空间的撤销，如果大于0，说明需要腾出部分Block所占用的空间
    val numBytesToFree = math.max(0, numBytes - memoryFree)
    acquireMemory(blockId, numBytes, numBytesToFree)
  }

  /**
   * Acquire N bytes of storage memory for the given block, evicting existing ones if necessary.
   *
   * @param blockId the ID of the block we are acquiring storage memory for
   * @param numBytesToAcquire the size of this block
   * @param numBytesToFree the amount of space to be freed through evicting blocks
   * @return whether all N bytes were successfully granted.
   */
  def acquireMemory(
      blockId: BlockId,
      numBytesToAcquire: Long,
      numBytesToFree: Long): Boolean = lock.synchronized {
    // 参数检查
    assert(numBytesToAcquire >= 0)
    assert(numBytesToFree >= 0)
    assert(memoryUsed <= poolSize)
    // 腾出numBytesToFree属性指定大小的空间
    if (numBytesToFree > 0) {
      memoryStore.evictBlocksToFreeSpace(Some(blockId), numBytesToFree, memoryMode)
    }
    // NOTE: If the memory store evicts blocks, then those evictions will synchronously call
    // back into this StorageMemoryPool in order to free memory. Therefore, these variables
    // should have been updated.
    // 判断内存是否充足
    // 注意：如果内存存储驱逐区块，那么这些驱逐将同步回调到这个StorageMemoryPool，以便释放内存。因此，这些变量应该已经更新了。
    val enoughMemory = numBytesToAcquire <= memoryFree
    // 增加已经使用的内存大小
    if (enoughMemory) {
      _memoryUsed += numBytesToAcquire
    }
    // 返回布尔值
    enoughMemory
  }

  /** 用于释放内存 */
  def releaseMemory(size: Long): Unit = lock.synchronized {
    if (size > _memoryUsed) {
      logWarning(s"Attempted to release $size bytes of storage " +
        s"memory when we only have ${_memoryUsed} bytes")
      _memoryUsed = 0
    } else {
      _memoryUsed -= size
    }
  }

  /** 释放所有使用的内存 */
  def releaseAllMemory(): Unit = lock.synchronized {
    _memoryUsed = 0
  }

  /**
   * Free space to shrink the size of this storage memory pool by `spaceToFree` bytes.
   * Note: this method doesn't actually reduce the pool size but relies on the caller to do so.
   * 用于释放指定大小的空间，缩小内存池的大小
   * 注意：本方法实际上并不缩小池的大小，而是依靠调用者来实现。
   *
   * if spaceToFree > memoryFree:
   *     驱逐块
   *     return 驱逐块的大小 + (spaceToFree - memoryFree)
   * else:
   *    return spaceToFree
   *
   * @return number of bytes to be removed from the pool's capacity.
   *         要从池的容量中删除的字节数。
   */
  def freeSpaceToShrinkPool(spaceToFree: Long): Long = lock.synchronized {
    /* 可以释放的不使用的内存 */
    // min = spaceToFree => remainingSpaceToFree = 0
    // min = memoryFree => remainingSpaceToFree = spaceToFree - memoryFree 即需要释放内存
    val spaceFreedByReleasingUnusedMemory = math.min(spaceToFree, memoryFree)
    val remainingSpaceToFree = spaceToFree - spaceFreedByReleasingUnusedMemory
    /* 用于释放不使用的内存不足 */
    if (remainingSpaceToFree > 0) {
      // If reclaiming free memory did not adequately shrink the pool, begin evicting blocks:
      // 如果回收空闲内存没有充分收缩池子，就开始驱逐块。
      val spaceFreedByEviction =
        memoryStore.evictBlocksToFreeSpace(None, remainingSpaceToFree, memoryMode)
      // When a block is released, BlockManager.dropFromMemory() calls releaseMemory(), so we do
      // not need to decrement _memoryUsed here. However, we do need to decrement the pool size.
      // 当一个块被释放时，BlockManager.dropFromMemory()会调用releaseMemory()，
      // 所以我们不需要在这里递减_memoryUsed。但是，我们确实需要减少池的大小。
      spaceFreedByReleasingUnusedMemory + spaceFreedByEviction
    } else {
      spaceFreedByReleasingUnusedMemory
    }
  }
}
