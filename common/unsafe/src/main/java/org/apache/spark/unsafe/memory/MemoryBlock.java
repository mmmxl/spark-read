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

package org.apache.spark.unsafe.memory;

import javax.annotation.Nullable;

import org.apache.spark.unsafe.Platform;

/**
 * A consecutive block of memory, starting at a {@link MemoryLocation} with a fixed size.
 * 一个连续的内存块，从一个{@link MemoryLocation}开始，大小固定。
 * 类似os的page 可能位于JVM的堆上也可能位于堆外
 * 比父类MemoryLocation多了一个length来确定大小
 */
public class MemoryBlock extends MemoryLocation {

  /** Special `pageNumber` value for pages which were not allocated by TaskMemoryManagers
   *  任务内存管理器未分配的页面的特殊`pageNumber`值 */
  public static final int NO_PAGE_NUMBER = -1;

  /**
   * Special `pageNumber` value for marking pages that have been freed in the TaskMemoryManager.
   * We set `pageNumber` to this value in TaskMemoryManager.freePage() so that MemoryAllocator
   * can detect if pages which were allocated by TaskMemoryManager have been freed in the TMM
   * before being passed to MemoryAllocator.free() (it is an error to allocate a page in
   * TaskMemoryManager and then directly free it in a MemoryAllocator without going through
   * the TMM freePage() call).
   * 特殊的"pageNumber"值，用于标记在TaskMemoryManager中被释放的页面。
   * 我们在TaskMemoryManager.freePage()中把`pageNumber`设置为这个值，
   * 这样MemoryAllocator就可以在传递给MemoryAllocator.free()之前，
   * 检测到由TaskMemoryManager分配的页面是否已经在TMM中被释放
   * （在TaskMemoryManager中分配一个页面，然后不经过TMM freePage()调用而直接在MemoryAllocator中释放是一个错误）。
   */
  public static final int FREED_IN_TMM_PAGE_NUMBER = -2;

  /**
   * Special `pageNumber` value for pages that have been freed(释放) by the MemoryAllocator. This allows
   * us to detect double-frees.
   * 对于已经被MemoryAllocator释放的页面，有一个特殊的`pageNumber`值。这使我们能够检测到双重释放。
   */
  public static final int FREED_IN_ALLOCATOR_PAGE_NUMBER = -3;

  /** 当前MemoryBlock的连续内存块的长度 */
  private final long length;

  /**
   * Optional page number; used when this MemoryBlock represents a page allocated by a
   * TaskMemoryManager. This field is public so that it can be modified by the TaskMemoryManager,
   * which lives in a different package.
   * 可选的页码；当这个MemoryBlock代表一个由TaskMemoryManager分配的页面时使用。
   * 这个字段是公开的，所以它可以被任务内存管理器修改，因为任务内存管理器位于不同的包中。
   *
   * 当前MemoryBlock的页号,TaskMemoryManager分配由MemoryBlock表示的Page时,将使用此属性。
   */
  public int pageNumber = NO_PAGE_NUMBER;

  public MemoryBlock(@Nullable Object obj, long offset, long length) {
    super(obj, offset);
    this.length = length;
  }

  /**
   * Returns the size of the memory block.
   * 返回memory block的长度
   */
  public long size() {
    return length;
  }

  /**
   * Creates a memory block pointing to the memory used by the long array.
   * 创建一个指向由长整型数组使用的内存的MemoryBlock
   */
  public static MemoryBlock fromLongArray(final long[] array) {
    return new MemoryBlock(array, Platform.LONG_ARRAY_OFFSET, array.length * 8L);
  }

  /**
   * Fills the memory block with the specified byte value.
   * 用指定的字节值填充内存块。
   */
  public void fill(byte value) {
    Platform.setMemory(obj, offset, length, value);
  }
}
