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

import scala.collection.mutable

import org.apache.spark.internal.Logging

/**
 * Implements policies and bookkeeping for sharing an adjustable-sized pool of memory between tasks.
 *
 * Tries to ensure that each task gets a reasonable share of memory, instead of some task ramping up
 * to a large amount first and then causing others to spill to disk repeatedly.
 * 尽量保证每个任务都能得到合理的内存份额，而不是一些任务先分到很大的量，然后导致其他任务反复溢出到磁盘。
 *
 * If there are N tasks, it ensures that each task can acquire at least 1 / 2N of the memory
 * before it has to spill, and at most 1 / N. Because N varies dynamically, we keep track of the
 * set of active tasks and redo the calculations of 1 / 2N and 1 / N in waiting tasks whenever this
 * set changes. This is all done by synchronizing access to mutable state and using wait() and
 * notifyAll() to signal changes to callers. Prior to Spark 1.6, this arbitration of memory across
 * tasks was performed by the ShuffleMemoryManager.
 * 如果有N个任务，它确保每个任务在不得不溢出之前至少可以获得1/2N的内存，最多可以获得1 / N。
 * 因为N是动态变化的，所以我们一直跟踪活跃任务的集合，并且每当这个集合发生变化时，我们就会重新计算等待任务中的1/2N和1/N。
 * 这一切都通过同步访问可变状态并使用wait()和notifyAll()向调用者发出变化信号来完成。
 * 在Spark 1.6之前，这种对内存的仲裁跨越了任务由ShuffleMemoryManager执行。
 *
 * @param lock a [[MemoryManager]] instance to synchronize on
 * @param memoryMode the type of memory tracked by this pool (on- or off-heap)
 */
private[memory] class ExecutionMemoryPool(
    lock: Object,
    memoryMode: MemoryMode
  ) extends MemoryPool(lock) with Logging {

  private[this] val poolName: String = memoryMode match {
    case MemoryMode.ON_HEAP => "on-heap execution"
    case MemoryMode.OFF_HEAP => "off-heap execution"
  }

  /**
   * Map from taskAttemptId -> memory consumption in bytes
   * 任务尝试ID -> 消费内存的大小
   * \@@GuardedBy() 类似synchronized
   */
  @GuardedBy("lock")
  private val memoryForTask = new mutable.HashMap[Long, Long]()

  /** 获取任务尝试使用的总内存大小 */
  override def memoryUsed: Long = lock.synchronized {
    memoryForTask.values.sum
  }

  /**
   * Returns the memory consumption, in bytes, for the given task.
   * 得到给定任务尝试id的消费内存大小
   */
  def getMemoryUsageForTask(taskAttemptId: Long): Long = lock.synchronized {
    memoryForTask.getOrElse(taskAttemptId, 0L)
  }

  /**
   * Try to acquire up to `numBytes` of memory for the given task and return the number of bytes
   * obtained, or 0 if none can be allocated.
   * 尝试为给定的任务获取最多`numBytes`的内存，并返回获取的字节数，如果不能分配，则返回0。
   * This call may block until there is enough free memory in some situations, to make sure each
   * task has a chance to ramp up to at least 1 / 2N of the total memory pool (where N is the # of
   * active tasks) before it is forced to spill. This can happen if the number of tasks increase
   * but an older task had a lot of memory already.
   * 在某些情况下，这个调用可能会阻塞，直到有足够的空闲内存，
   * 以确保每个任务有机会在被迫溢出之前至少提升到总内存池的1/2N（其中N是活动任务的数量）。
   * 如果任务数量增加，但一个旧任务已经有了很多内存，就会发生这种情况。
   *
   * 步骤:
   * 1.如果memoryForTask没有记录TaskAttemptId,则将TaskAttemptId加入memoryForTask, 唤醒其他等待获取Execution MemoryPool的锁的线程
   * 2.不断循环
   *   2.1 获取task数量N,得到TaskAttemptId当前内存大小,计算出min
   *   2.2 maybeGrowPool(): 回收StorageMemoryPool从当前ExecutionMemoryPool借用的内存,
   *                        如果StorageMemoryPool有空闲空间,就借用(会导致StorageMemory的Block被驱逐出去)
   *       maxPoolSize = computeMaxPoolSize():计算内存池大小
   *   2.3 计算出maxPoolSize,maxMemoryPerTask,minMemoryPerTask
   *   2.4 计算出当前任务真正可以申请的内存(toGrant)
   *   2.5 > 如果toGrant小于需要的内存并且 toGrant+curMem < minMemoryPerTask就线程等待
   *         需要等待其他任务尝试释放内存
   *       > 其他情况,返回toGrant
   *
   *
   *
   * @param numBytes number of bytes to acquire
   * @param taskAttemptId the task attempt acquiring memory
   * @param maybeGrowPool a callback that potentially grows the size of this pool. It takes in
   *                      one parameter (Long) that represents the desired amount of memory by
   *                      which this pool should be expanded.
   * @param computeMaxPoolSize a callback that returns the maximum allowable size of this pool
   *                           at this given moment. This is not a field because the max pool
   *                           size is variable in certain cases. For instance, in unified
   *                           memory management, the execution pool can be expanded by evicting
   *                           cached blocks, thereby shrinking the storage pool.
   *
   * @return the number of bytes granted to the task.
   */
  private[memory] def acquireMemory(
      numBytes: Long, // 需要获取的内存大小
      taskAttemptId: Long, // task的尝试id
      maybeGrowPool: Long => Unit = (additionalSpaceNeeded: Long) => Unit,
      computeMaxPoolSize: () => Long = () => poolSize): Long = lock.synchronized {
    assert(numBytes > 0, s"invalid number of bytes requested: $numBytes")

    // TODO: clean up this clunky method signature

    // Add this task to the taskMemory map just so we can keep an accurate count of the number
    // of active tasks, to let other tasks ramp down their memory in calls to `acquireMemory`
    // 把这个任务添加到taskMemory map中，这样我们就可以准确地统计活动任务的数量，让其他任务在调用 "acquireMemory "时减少内存
    if (!memoryForTask.contains(taskAttemptId)) {
      memoryForTask(taskAttemptId) = 0L // 将taskAttemptId放入memoryForTask
      // This will later cause waiting tasks to wake up and check numTasks again
      // 这将会导致等待的任务被唤醒并再次检查numTasks
      lock.notifyAll()
    }

    // Keep looping until we're either sure that we don't want to grant this request (because this
    // task would have more than 1 / numActiveTasks of the memory) or we have enough free
    // memory to give it (we always let each task get at least 1 / (2 * numActiveTasks)).
    // TODO: simplify this to limit each task to its own slot
    while (true) {
      /* 获取当前激活的task数量 */
      val numActiveTasks = memoryForTask.keys.size
      /* 获取当前任务尝试所消费的内存 */
      val curMem = memoryForTask(taskAttemptId)

      // In every iteration of this loop, we should first try to reclaim any borrowed execution
      // space from storage. This is necessary because of the potential race condition where new
      // storage blocks may steal the free execution memory that this task was waiting for.
      // 在这个循环的每次迭代中，我们应该首先尝试从存储中回收任何借来的执行空间。
      // 这是必要的，因为潜在的竞赛条件，新的存储块可能会窃取这个任务等待的空闲执行内存。
      /* 从StorageMemoryPool回收或借用内存 */
      maybeGrowPool(numBytes - memoryFree)

      // Maximum size the pool would have after potentially growing the pool.
      // This is used to compute the upper bound of how much memory each task can occupy. This
      // must take into account potential free memory as well as the amount this pool currently
      // occupies. Otherwise, we may run into SPARK-12155 where, in unified memory management,
      // we did not take into account space that could have been freed by evicting cached blocks.
      // 池子可能增长后，池子的最大尺寸。
      // 这是用来计算每个任务可以占用多少内存的上限。这必须考虑到潜在的空闲内存以及这个池子当前占用的数量。
      // 否则，我们可能会遇到SPARK-12155，在统一的内存管理中，我们没有考虑到通过驱逐缓存块可以释放的空间。
      /* 计算内存池的大小 */
      val maxPoolSize = computeMaxPoolSize()
      /* 计算每个任务尝试最大可以使用的内存大小 */
      val maxMemoryPerTask = maxPoolSize / numActiveTasks
      /* 计算每个任务尝试最小可以使用的内存大小 */
      val minMemoryPerTask = poolSize / (2 * numActiveTasks)

      // How much we can grant this task; keep its share within 0 <= X <= 1 / numActiveTasks
      /* maxPoolSize:100m  N:10  maxMemoryPerTask:10m  minMemoryPerTask:5m
         curMem=2m  numBytes=10m  maxToGrant=min(10m, max(0, 10m - 2m)) = 8m  */
      val maxToGrant = math.min(numBytes, math.max(0, maxMemoryPerTask - curMem))
      // Only give it as much memory as is free, which might be none if it reached 1 / numTasks
      /* 计算当前任务尝试真正可以申请获取的内存大小 */
      val toGrant = math.min(maxToGrant, memoryFree)

      // We want to let each task get at least 1 / (2 * numActiveTasks) before blocking;
      // if we can't give it this much now, wait for other tasks to free up memory
      // (this happens if older tasks allocated lots of memory before N grew)
      if (toGrant < numBytes && curMem + toGrant < minMemoryPerTask) {
        logInfo(s"TID $taskAttemptId waiting for at least 1/2N of $poolName pool to be free")
        /* 内存不足,使当前线程处于等待状态 */
        lock.wait()
      } else {
        memoryForTask(taskAttemptId) += toGrant
        return toGrant
      }
    }
    0L  // Never reached
  }

  /**
   * Release `numBytes` of memory acquired by the given task.
   * 释放被task获取的指定大小内存
   */
  def releaseMemory(numBytes: Long, taskAttemptId: Long): Unit = lock.synchronized {
    // 得到taskAttemptId的内存大小
    val curMem = memoryForTask.getOrElse(taskAttemptId, 0L)
    // 如果需要释放的内存大小大于所持有的内存,需要释放的内存就是curMem
    var memoryToFree = if (curMem < numBytes) {
      logWarning(
        s"Internal error: release called on $numBytes bytes but task only has $curMem bytes " +
          s"of memory from the $poolName pool")
      curMem
    } else {
      numBytes
    }
    // 如果taskAttemptId在memoryForTask中
    // value删去memoryToFree,如果<=,就把key从memoryForTask删去
    if (memoryForTask.contains(taskAttemptId)) {
      memoryForTask(taskAttemptId) -= memoryToFree
      if (memoryForTask(taskAttemptId) <= 0) {
        memoryForTask.remove(taskAttemptId)
      }
    }
    // 唤醒acquireMemory中等待的进程来重新获取内存
    lock.notifyAll() // Notify waiters in acquireMemory() that memory has been freed
  }

  /**
   * Release all memory for the given task and mark it as inactive (e.g. when a task ends).
   * 释放指定任务的所有内存，并将其标记为非活动状态（例如，当任务结束时）
   * @return the number of bytes freed.
   */
  def releaseAllMemoryForTask(taskAttemptId: Long): Long = lock.synchronized {
    val numBytesToFree = getMemoryUsageForTask(taskAttemptId)
    releaseMemory(numBytesToFree, taskAttemptId)
    numBytesToFree
  }

}
