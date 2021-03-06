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

/**
 * This package implements Spark's memory management system. This system consists of two main
 * components, a JVM-wide memory manager and a per-task manager:
 *
 *  - [[org.apache.spark.memory.MemoryManager]] manages Spark's overall(总体) memory usage within a JVM.
 *    This component(组件) implements the policies for dividing the available memory across tasks and for
 *    allocating memory between storage (memory used caching and data transfer) and execution
 *    (memory used by computations, such as shuffles, joins, sorts, and aggregations).
 *  - [[org.apache.spark.memory.TaskMemoryManager]] manages the memory allocated by individual
 *    tasks. Tasks interact with TaskMemoryManager and never directly interact with the JVM-wide
 *    MemoryManager.
 *
 * Internally, each of these components have additional abstractions for memory bookkeeping:
 *
 *  - [[org.apache.spark.memory.MemoryConsumer]]s are clients of the TaskMemoryManager and
 *    correspond to individual operators and data structures within a task. The TaskMemoryManager
 *    receives memory allocation requests from MemoryConsumers and issues callbacks to consumers
 *    in order to trigger spilling when running low on memory.
 *    MemoryConsumer是TaskMemoryManager的客户端，对应于任务中的各个运算符和数据结构。
 *    TaskMemoryManager接收来自MemoryConsumers的内存分配请求，并向消费者发出回调。
 *    以便在内存不足时触发溢出。
 *  - [[org.apache.spark.memory.MemoryPool]]s are a bookkeeping abstraction used by the
 *    MemoryManager to track the division of memory between storage and execution.
 *    MemoryPool是MemoryManager使用的记账抽象，用于跟踪存储和执行之间的内存划分。
 *
 * Diagrammatically:
 *
 * {{{
 *                                                              +---------------------------+
 *       +-------------+                                        |       MemoryManager       |
 *       | MemConsumer |----+                                   |                           |
 *       +-------------+    |    +-------------------+          |  +---------------------+  |
 *                          +--->| TaskMemoryManager |----+     |  |OnHeapStorageMemPool |  |
 *       +-------------+    |    +-------------------+    |     |  +---------------------+  |
 *       | MemConsumer |----+                             |     |                           |
 *       +-------------+         +-------------------+    |     |  +---------------------+  |
 *                               | TaskMemoryManager |----+     |  |OffHeapStorageMemPool|  |
 *                               +-------------------+    |     |  +---------------------+  |
 *                                                        +---->|                           |
 *                                        *               |     |  +---------------------+  |
 *                                        *               |     |  |OnHeapExecMemPool    |  |
 *       +-------------+                  *               |     |  +---------------------+  |
 *       | MemConsumer |----+                             |     |                           |
 *       +-------------+    |    +-------------------+    |     |  +---------------------+  |
 *                          +--->| TaskMemoryManager |----+     |  |OffHeapExecMemPool   |  |
 *                               +-------------------+          |  +---------------------+  |
 *                                                              |                           |
 *                                                              +---------------------------+
 * }}}
 *
 *
 * There are two implementations of [[org.apache.spark.memory.MemoryManager]] which vary in how
 * they handle the sizing of their memory pools:
 *
 *  - [[org.apache.spark.memory.UnifiedMemoryManager]], the default in Spark 1.6+, enforces soft
 *    boundaries between storage and execution memory, allowing requests for memory in one region
 *    to be fulfilled by borrowing memory from the other.
 *    在Spark 1.6+中的默认值，强制执行存储和执行内存之间的软边界，允许通过从另一个区域借用内存来满足对一个区域内存的请求。
 *  - [[org.apache.spark.memory.StaticMemoryManager]] enforces hard boundaries between storage
 *    and execution memory by statically partitioning Spark's memory and preventing storage and
 *    execution from borrowing memory from each other. This mode is retained only for legacy
 *    compatibility purposes.
 *    通过静态分区Spark的内存，防止存储和执行相互借用内存，从而强制执行存储和执行内存之间的硬边界。这种模式只保留给传统的
 *    兼容性的目的。
 */
package object memory
