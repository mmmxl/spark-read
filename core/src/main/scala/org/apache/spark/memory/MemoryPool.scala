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

/**
 * Manages bookkeeping for an adjustable-sized region of memory. This class is internal to
 * the [[MemoryManager]]. See subclasses for more details.
 * 管理一个可调节大小的内存区域的记账。该类是[[MemoryManager]]的内部类。详见子类。
 * 里面定义关于改变和获取内存池子总大小和使用大小大方法
 * @param lock a [[MemoryManager]] instance, used for synchronization. We purposely erase the type
 *             to `Object` to avoid programming errors, since this object should only be used for
 *             synchronization purposes.
 */
private[memory] abstract class MemoryPool(lock: Object) {

  @GuardedBy("lock")
  private[this] var _poolSize: Long = 0

  /**
   * Returns the current size of the pool, in bytes.
   * 返回内存池的大小 bytes为单位
   */
  final def poolSize: Long = lock.synchronized {
    _poolSize
  }

  /**
   * Returns the amount of free memory in the pool, in bytes.
   * 返回池中空闲内存的数量，单位为字节
   */
  final def memoryFree: Long = lock.synchronized {
    _poolSize - memoryUsed
  }

  /**
   * Expands the pool by `delta` bytes.
   * 扩大池子`delta`字节
   */
  final def incrementPoolSize(delta: Long): Unit = lock.synchronized {
    require(delta >= 0)
    _poolSize += delta
  }

  /**
   * Shrinks the pool by `delta` bytes.
   * 缩小池子`delta`字节
   */
  final def decrementPoolSize(delta: Long): Unit = lock.synchronized {
    require(delta >= 0)
    require(delta <= _poolSize)
    require(_poolSize - delta >= memoryUsed)
    _poolSize -= delta
  }

  /**
   * Returns the amount of used memory in this pool (in bytes).
   * 返回池子中被使用的内存大小
   */
  def memoryUsed: Long
}
