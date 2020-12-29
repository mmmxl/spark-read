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

import java.io._
import java.util.Comparator

import scala.collection.BufferedIterator
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.{DeserializationStream, Serializer, SerializerManager}
import org.apache.spark.storage.{BlockId, BlockManager}
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.collection.ExternalAppendOnlyMap.HashComparator

/**
 * :: DeveloperApi ::
 * An append-only map that spills sorted content to disk when there is insufficient space for it
 * to grow.
 *
 * This map takes two passes over the data:
 *
 *   (1) Values are merged into combiners, which are sorted and spilled to disk as necessary
 *   (2) Combiners are read from disk and merged together
 *
 * The setting of the spill threshold faces the following trade-off: If the spill threshold is
 * too high, the in-memory map may occupy more memory than is available, resulting in OOM.
 * However, if the spill threshold is too low, we spill frequently and incur unnecessary disk
 * writes. This may lead to a performance regression compared to the normal case of using the
 * non-spilling AppendOnlyMap.
 *
 * 当聚合时，内存不足可以溢写到磁盘中
 * 这个map会对数据进行2次扫描:
 *  (1) 值被合并到combiners中，必要时进行排序并溢出到磁盘。
 *  (2) 从磁盘上读取combiners，并将其合并在一起。
 * 溢出阈值的设置面临以下权衡：如果溢出阈值是过高，内存中的地图可能会占用超过可用的内存，导致OOM。
 * 然而，如果溢出阈值太低，我们会频繁地溢出，并产生不必要的写磁盘。
 * 这可能会导致与正常情况下的使用
 * 与正常情况下使用非溢出的AppendOnlyMap相比，这可能会导致性能倒退。
 *
 */
@DeveloperApi
class ExternalAppendOnlyMap[K, V, C](
    createCombiner: V => C,
    mergeValue: (C, V) => C,
    mergeCombiners: (C, C) => C,
    serializer: Serializer = SparkEnv.get.serializer,
    blockManager: BlockManager = SparkEnv.get.blockManager,
    context: TaskContext = TaskContext.get(),
    serializerManager: SerializerManager = SparkEnv.get.serializerManager)
  extends Spillable[SizeTracker](context.taskMemoryManager())
  with Serializable
  with Logging
  with Iterable[(K, C)] {
  // 没有taskContext,直接报错
  if (context == null) {
    throw new IllegalStateException(
      "Spillable collections should not be instantiated outside of tasks")
  }

  // Backwards-compatibility constructor for binary compatibility
  def this(
      createCombiner: V => C,
      mergeValue: (C, V) => C,
      mergeCombiners: (C, C) => C,
      serializer: Serializer,
      blockManager: BlockManager) {
    this(createCombiner, mergeValue, mergeCombiners, serializer, blockManager, TaskContext.get())
  }

  /**
   * Exposed for testing
   */
  @volatile private[collection] var currentMap = new SizeTrackingAppendOnlyMap[K, C]
  private val spilledMaps = new ArrayBuffer[DiskMapIterator]
  private val sparkConf = SparkEnv.get.conf
  private val diskBlockManager = blockManager.diskBlockManager

  /**
   * Size of object batches when reading/writing from serializers.
   *
   * Objects are written in batches, with each batch using its own serialization stream. This
   * cuts down on the size of reference-tracking maps constructed when deserializing a stream.
   *
   * NOTE: Setting this too low can cause excessive copying when serializing, since some serializers
   * grow internal data structures by growing + copying every time the number of objects doubles.
   */
  private val serializerBatchSize = sparkConf.getLong("spark.shuffle.spill.batchSize", 10000)

  // Number of bytes spilled in total
  // 溢出的字节数
  private var _diskBytesSpilled = 0L
  def diskBytesSpilled: Long = _diskBytesSpilled

  // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
  private val fileBufferSize =
    sparkConf.getSizeAsKb("spark.shuffle.file.buffer", "32k").toInt * 1024

  // Write metrics
  private val writeMetrics: ShuffleWriteMetrics = new ShuffleWriteMetrics()

  // Peak size of the in-memory map observed so far, in bytes
  // 到目前为止观察到的内存中map的峰值大小，以字节为单位。
  private var _peakMemoryUsedBytes: Long = 0L
  def peakMemoryUsedBytes: Long = _peakMemoryUsedBytes

  private val keyComparator = new HashComparator[K]
  private val ser = serializer.newInstance()

  @volatile private var readingIterator: SpillableIterator = null

  /**
   * Number of files this map has spilled so far.
   * Exposed for testing.
   */
  private[collection] def numSpills: Int = spilledMaps.size

  /**
   * Insert the given key and value into the map.
   */
  def insert(key: K, value: V): Unit = {
    insertAll(Iterator((key, value)))
  }

  /**
   * Insert the given iterator of keys and values into the map.
   *
   * When the underlying map needs to grow, check if the global pool of shuffle memory has
   * enough room for this to happen. If so, allocate the memory required to grow the map;
   * otherwise, spill the in-memory map to disk.
   *
   * The shuffle memory usage of the first trackMemoryThreshold entries is not tracked.
   *
   * 1.未初始化currentMap,报错
   * 2.遍历Iterator
   *   2.1 如果currentMap的估计内存大小 > _peakMemoryUsedBytes,就更新_peakMemoryUsedBytes
   *   2.2 如果currentMap的内存需要溢写了,就溢写出去[[maybeSpill]],创建一个新的SizeTrackingAppendOnlyMap
   * 3.运行聚合函数,结果写入AppendOnlyMap
   * 4.增加上次溢写后读取元素数量
   */
  def insertAll(entries: Iterator[Product2[K, V]]): Unit = {
    // map未初始化
    if (currentMap == null) {
      throw new IllegalStateException(
        "Cannot insert new elements into a map after calling iterator")
    }
    // An update function for the map that we reuse across entries to avoid allocating
    // a new closure each time
    // 重复使用的map更新函数，以避免每次都分配一个新的闭包。
    var curEntry: Product2[K, V] = null // Product2是Tuple2继承的接口
    val update: (Boolean, C) => C = (hadVal, oldVal) => {
      /* 有值走mergeValue() 无值走createCombiner() */
      if (hadVal) mergeValue(oldVal, curEntry._2) else createCombiner(curEntry._2)
    }

    while (entries.hasNext) {
      curEntry = entries.next()
      // 得到currentMap的估算内存大小,如果大于记录的峰值(_peakMemoryUsedBytes)就更新
      val estimatedSize = currentMap.estimateSize()
      if (estimatedSize > _peakMemoryUsedBytes) {
        _peakMemoryUsedBytes = estimatedSize
      }
      if (maybeSpill(currentMap, estimatedSize)) {
        currentMap = new SizeTrackingAppendOnlyMap[K, C]
      }
      // 在SizeTrackingAppendOnlyMap中使用update()
      currentMap.changeValue(curEntry._1, update)
      addElementsRead()
    }
  }

  /**
   * Insert the given iterable of keys and values into the map.
   *
   * When the underlying map needs to grow, check if the global pool of shuffle memory has
   * enough room for this to happen. If so, allocate the memory required to grow the map;
   * otherwise, spill the in-memory map to disk.
   *
   * The shuffle memory usage of the first trackMemoryThreshold entries is not tracked.
   */
  def insertAll(entries: Iterable[Product2[K, V]]): Unit = {
    insertAll(entries.iterator)
  }

  /**
   * Sort the existing contents of the in-memory map and spill them to a temporary file on disk.
   * 对内存中map进行排序，并将其溢写到磁盘上的临时文件。
   * destructive(破坏)
   * 1.对currentMap原地排序
   * 2.溢写内存到磁盘
   */
  override protected[this] def spill(collection: SizeTracker): Unit = {
    val inMemoryIterator = currentMap.destructiveSortedIterator(keyComparator)
    val diskMapIterator = spillMemoryIteratorToDisk(inMemoryIterator)
    spilledMaps += diskMapIterator
  }

  /**
   * Force to spilling the current in-memory collection to disk to release memory,
   * It will be called by TaskMemoryManager when there is not enough memory for the task.
   */
  override protected[this] def forceSpill(): Boolean = {
    if (readingIterator != null) {
      val isSpilled = readingIterator.spill()
      if (isSpilled) {
        currentMap = null
      }
      isSpilled
    } else if (currentMap.size > 0) {
      spill(currentMap)
      currentMap = new SizeTrackingAppendOnlyMap[K, C]
      true
    } else {
      false
    }
  }

  /**
   * Spill the in-memory Iterator to a temporary file on disk.
   */
  private[this] def spillMemoryIteratorToDisk(inMemoryIterator: Iterator[(K, C)])
      : DiskMapIterator = {
    val (blockId, file) = diskBlockManager.createTempLocalBlock()
    val writer = blockManager.getDiskWriter(blockId, file, ser, fileBufferSize, writeMetrics)
    var objectsWritten = 0

    // List of batch sizes (bytes) in the order they are written to disk
    // 按写入磁盘的顺序列出批次大小（字节）
    val batchSizes = new ArrayBuffer[Long]

    // Flush the disk writer's contents to disk, and update relevant variables
    // 将磁盘写入器的内容刷新到磁盘上，并更新相关变量。
    def flush(): Unit = {
      val segment = writer.commitAndGet()
      batchSizes += segment.length
      _diskBytesSpilled += segment.length
      objectsWritten = 0
    }

    var success = false
    try {
      // 使用迭代器写,到序列化批大小就flush
      while (inMemoryIterator.hasNext) {
        val kv = inMemoryIterator.next()
        writer.write(kv._1, kv._2)
        objectsWritten += 1

        if (objectsWritten == serializerBatchSize) {
          flush()
        }
      }
      // 剩余元素flush
      if (objectsWritten > 0) {
        flush()
        writer.close()
      } else {
        writer.revertPartialWritesAndClose()
      }
      success = true
    } finally {
      if (!success) {
        // This code path only happens if an exception was thrown above before we set success;
        // close our stuff and let the exception be thrown further
        // 只有在我们设置成功之前，上面发生了异常，才会发生这个代码路径。
        // 关闭我们的东西，让异常进一步被抛出。
        writer.revertPartialWritesAndClose()
        if (file.exists()) {
          if (!file.delete()) {
            logWarning(s"Error deleting ${file}")
          }
        }
      }
    }

    new DiskMapIterator(file, blockId, batchSizes)
  }

  /**
   * Returns a destructive iterator for iterating over the entries of this map.
   * If this iterator is forced spill to disk to release memory when there is not enough memory,
   * it returns pairs from an on-disk map.
   */
  def destructiveIterator(inMemoryIterator: Iterator[(K, C)]): Iterator[(K, C)] = {
    readingIterator = new SpillableIterator(inMemoryIterator)
    readingIterator.toCompletionIterator
  }

  /**
   * Return a destructive iterator that merges the in-memory map with the spilled maps.
   * If no spill has occurred, simply return the in-memory map's iterator.
   * 返回一个破坏性迭代器，将内存中的map与溢出的map合并。
   * 如果没有发生溢出，只需返回内存中地图的迭代器。
   */
  override def iterator: Iterator[(K, C)] = {
    if (currentMap == null) {
      throw new IllegalStateException(
        "ExternalAppendOnlyMap.iterator is destructive and should only be called once.")
    }
    if (spilledMaps.isEmpty) {
      // 返回内存中的迭代器
      destructiveIterator(currentMap.iterator)
    } else {
      // 将内存中的map与溢出的map合并,返回迭代器
      new ExternalIterator()
    }
  }

  private def freeCurrentMap(): Unit = {
    if (currentMap != null) {
      currentMap = null // So that the memory can be garbage-collected
      releaseMemory()
    }
  }

  /**
   * An iterator that sort-merges (K, C) pairs from the in-memory map and the spilled maps
   * 一个迭代器，从内存映射和溢出map中排序合并键值对。
   */
  private class ExternalIterator extends Iterator[(K, C)] {

    // A queue that maintains a buffer for each stream we are currently merging
    // This queue maintains the invariant that it only contains non-empty buffers
    // 一个堆
    private val mergeHeap = new mutable.PriorityQueue[StreamBuffer]

    // Input streams are derived both from the in-memory map and spilled maps on disk
    // The in-memory map is sorted in place, while the spilled maps are already in sorted order
    // 输入流来自于内存中的map和磁盘上的spilledMaps两个方面。
    // 内存中的地图是原地排序的，而溢出的地图已经是按排序顺序排列了
    private val sortedMap: Iterator[(K, C)] = destructiveIterator(
      currentMap.destructiveSortedIterator(keyComparator))
    private val inputStreams: Seq[BufferedIterator[(K, C)]] = (Seq(sortedMap) ++ spilledMaps).map(it => it.buffered)
    // inputStreams入堆
    inputStreams.foreach { it =>
      val kcPairs = new ArrayBuffer[(K, C)]
      readNextHashCode(it, kcPairs)
      if (kcPairs.length > 0) {
        mergeHeap.enqueue(new StreamBuffer(it, kcPairs))
      }
    }

    /**
     * Fill a buffer with the next set of keys with the same hash code from a given iterator. We
     * read streams one hash code at a time to ensure we don't miss elements when they are merged.
     *
     * Assumes the given iterator is in sorted order of hash code.
     * 用给定iterator中的下一组具有相同哈希码的键填充缓冲区。
     * 我们一次只读取相同哈希码的流，以确保在合并时不会遗漏元素。
     * 假设给定的迭代器是按哈希码的顺序排列的。
     *
     * @param it iterator to read from
     * @param buf buffer to write the results into
     */
    private def readNextHashCode(it: BufferedIterator[(K, C)], buf: ArrayBuffer[(K, C)]): Unit = {
      if (it.hasNext) {
        var kc = it.next()
        buf += kc
        val minHash = hashKey(kc)
        while (it.hasNext && it.head._1.hashCode() == minHash) {
          kc = it.next()
          buf += kc
        }
      }
    }

    /**
     * If the given buffer contains a value for the given key, merge that value into
     * baseCombiner and remove the corresponding (K, C) pair from the buffer.
     */
    private def mergeIfKeyExists(key: K, baseCombiner: C, buffer: StreamBuffer): C = {
      var i = 0
      while (i < buffer.pairs.length) {
        val pair = buffer.pairs(i)
        if (pair._1 == key) {
          // Note that there's at most one pair in the buffer with a given key, since we always
          // merge stuff in a map before spilling, so it's safe to return after the first we find
          removeFromBuffer(buffer.pairs, i)
          return mergeCombiners(baseCombiner, pair._2)
        }
        i += 1
      }
      baseCombiner
    }

    /**
     * Remove the index'th element from an ArrayBuffer in constant time, swapping another element
     * into its place. This is more efficient than the ArrayBuffer.remove method because it does
     * not have to shift all the elements in the array over. It works for our array buffers because
     * we don't care about the order of elements inside, we just want to search them for a key.
     */
    private def removeFromBuffer[T](buffer: ArrayBuffer[T], index: Int): T = {
      val elem = buffer(index)
      buffer(index) = buffer(buffer.size - 1)  // This also works if index == buffer.size - 1
      buffer.reduceToSize(buffer.size - 1)
      elem
    }

    /**
     * Return true if there exists an input stream that still has unvisited pairs.
     */
    override def hasNext: Boolean = mergeHeap.nonEmpty

    /**
     * Select a key with the minimum hash, then combine all values with the same key from all
     * input streams.
     */
    override def next(): (K, C) = {
      if (mergeHeap.isEmpty) {
        throw new NoSuchElementException
      }
      // Select a key from the StreamBuffer that holds the lowest key hash
      val minBuffer = mergeHeap.dequeue()
      val minPairs = minBuffer.pairs
      val minHash = minBuffer.minKeyHash
      val minPair = removeFromBuffer(minPairs, 0)
      val minKey = minPair._1
      var minCombiner = minPair._2
      assert(hashKey(minPair) == minHash)

      // For all other streams that may have this key (i.e. have the same minimum key hash),
      // merge in the corresponding value (if any) from that stream
      val mergedBuffers = ArrayBuffer[StreamBuffer](minBuffer)
      while (mergeHeap.nonEmpty && mergeHeap.head.minKeyHash == minHash) {
        val newBuffer = mergeHeap.dequeue()
        minCombiner = mergeIfKeyExists(minKey, minCombiner, newBuffer)
        mergedBuffers += newBuffer
      }

      // Repopulate each visited stream buffer and add it back to the queue if it is non-empty
      mergedBuffers.foreach { buffer =>
        if (buffer.isEmpty) {
          readNextHashCode(buffer.iterator, buffer.pairs)
        }
        if (!buffer.isEmpty) {
          mergeHeap.enqueue(buffer)
        }
      }

      (minKey, minCombiner)
    }

    /**
     * A buffer for streaming from a map iterator (in-memory or on-disk) sorted by key hash.
     * Each buffer maintains all of the key-value pairs with what is currently the lowest hash
     * code among keys in the stream. There may be multiple keys if there are hash collisions.
     * Note that because when we spill data out, we only spill one value for each key, there is
     * at most one element for each key.
     *
     * StreamBuffers are ordered by the minimum key hash currently available in their stream so
     * that we can put them into a heap and sort that.
     *
     * 从map迭代器(内存或磁盘)中读取按键值散列排序的缓冲区。每一个缓冲区都保存了所有的键值对，并以当前最低的哈希值为基准。
     * 流中各键之间的代码。如果有哈希碰撞，可能会有多个键。
     * 请注意，因为当我们把数据溢出时，我们只为每个键溢出一个值，所以每个键最多只有一个元素。
     *
     * StreamBuffers是按其流中当前可用的最小key哈希值排序的，所以我们可以把它们放在一个堆里，然后分类。
     */
    private class StreamBuffer(
        val iterator: BufferedIterator[(K, C)],
        val pairs: ArrayBuffer[(K, C)])
      extends Comparable[StreamBuffer] {

      def isEmpty: Boolean = pairs.length == 0

      // Invalid if there are no more pairs in this stream
      def minKeyHash: Int = {
        assert(pairs.length > 0)
        hashKey(pairs.head)
      }

      override def compareTo(other: StreamBuffer): Int = {
        // descending order because mutable.PriorityQueue dequeues the max, not the min
        if (other.minKeyHash < minKeyHash) -1 else if (other.minKeyHash == minKeyHash) 0 else 1
      }
    }
  }

  /**
   * An iterator that returns (K, C) pairs in sorted order from an on-disk map
   */
  private class DiskMapIterator(file: File, blockId: BlockId, batchSizes: ArrayBuffer[Long])
    extends Iterator[(K, C)]
  {
    private val batchOffsets = batchSizes.scanLeft(0L)(_ + _)  // Size will be batchSize.length + 1
    assert(file.length() == batchOffsets.last,
      "File length is not equal to the last batch offset:\n" +
      s"    file length = ${file.length}\n" +
      s"    last batch offset = ${batchOffsets.last}\n" +
      s"    all batch offsets = ${batchOffsets.mkString(",")}"
    )

    private var batchIndex = 0  // Which batch we're in
    private var fileStream: FileInputStream = null

    // An intermediate stream that reads from exactly one batch
    // This guards against pre-fetching and other arbitrary behavior of higher level streams
    private var deserializeStream: DeserializationStream = null
    private var nextItem: (K, C) = null
    private var objectsRead = 0

    /**
     * Construct a stream that reads only from the next batch.
     */
    private def nextBatchStream(): DeserializationStream = {
      // Note that batchOffsets.length = numBatches + 1 since we did a scan above; check whether
      // we're still in a valid batch.
      if (batchIndex < batchOffsets.length - 1) {
        if (deserializeStream != null) {
          deserializeStream.close()
          fileStream.close()
          deserializeStream = null
          fileStream = null
        }

        val start = batchOffsets(batchIndex)
        fileStream = new FileInputStream(file)
        fileStream.getChannel.position(start)
        batchIndex += 1

        val end = batchOffsets(batchIndex)

        assert(end >= start, "start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))

        val bufferedStream = new BufferedInputStream(ByteStreams.limit(fileStream, end - start))
        val wrappedStream = serializerManager.wrapStream(blockId, bufferedStream)
        ser.deserializeStream(wrappedStream)
      } else {
        // No more batches left
        cleanup()
        null
      }
    }

    /**
     * Return the next (K, C) pair from the deserialization stream.
     *
     * If the current batch is drained, construct a stream for the next batch and read from it.
     * If no more pairs are left, return null.
     */
    private def readNextItem(): (K, C) = {
      try {
        val k = deserializeStream.readKey().asInstanceOf[K]
        val c = deserializeStream.readValue().asInstanceOf[C]
        val item = (k, c)
        objectsRead += 1
        if (objectsRead == serializerBatchSize) {
          objectsRead = 0
          deserializeStream = nextBatchStream()
        }
        item
      } catch {
        case e: EOFException =>
          cleanup()
          null
      }
    }

    override def hasNext: Boolean = {
      if (nextItem == null) {
        if (deserializeStream == null) {
          // In case of deserializeStream has not been initialized
          deserializeStream = nextBatchStream()
          if (deserializeStream == null) {
            return false
          }
        }
        nextItem = readNextItem()
      }
      nextItem != null
    }

    override def next(): (K, C) = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val item = nextItem
      nextItem = null
      item
    }

    private def cleanup() {
      batchIndex = batchOffsets.length  // Prevent reading any other batch
      if (deserializeStream != null) {
        deserializeStream.close()
        deserializeStream = null
      }
      if (fileStream != null) {
        fileStream.close()
        fileStream = null
      }
      if (file.exists()) {
        if (!file.delete()) {
          logWarning(s"Error deleting ${file}")
        }
      }
    }

    context.addTaskCompletionListener[Unit](context => cleanup())
  }

  private class SpillableIterator(var upstream: Iterator[(K, C)])
    extends Iterator[(K, C)] {

    private val SPILL_LOCK = new Object()

    private var cur: (K, C) = readNext()

    private var hasSpilled: Boolean = false

    def spill(): Boolean = SPILL_LOCK.synchronized {
      if (hasSpilled) {
        false
      } else {
        logInfo(s"Task ${context.taskAttemptId} force spilling in-memory map to disk and " +
          s"it will release ${org.apache.spark.util.Utils.bytesToString(getUsed())} memory")
        val nextUpstream = spillMemoryIteratorToDisk(upstream)
        assert(!upstream.hasNext)
        hasSpilled = true
        upstream = nextUpstream
        true
      }
    }

    private def destroy(): Unit = {
      freeCurrentMap()
      upstream = Iterator.empty
    }

    def toCompletionIterator: CompletionIterator[(K, C), SpillableIterator] = {
      CompletionIterator[(K, C), SpillableIterator](this, this.destroy)
    }

    def readNext(): (K, C) = SPILL_LOCK.synchronized {
      if (upstream.hasNext) {
        upstream.next()
      } else {
        null
      }
    }

    override def hasNext(): Boolean = cur != null

    override def next(): (K, C) = {
      val r = cur
      cur = readNext()
      r
    }
  }

  /** Convenience function to hash the given (K, C) pair by the key. */
  private def hashKey(kc: (K, C)): Int = ExternalAppendOnlyMap.hash(kc._1)

  override def toString(): String = {
    this.getClass.getName + "@" + java.lang.Integer.toHexString(this.hashCode())
  }
}

private[spark] object ExternalAppendOnlyMap {

  /**
   * Return the hash code of the given object. If the object is null, return a special hash code.
   */
  private def hash[T](obj: T): Int = {
    if (obj == null) 0 else obj.hashCode()
  }

  /**
   * A comparator which sorts arbitrary keys based on their hash codes.
   */
  private class HashComparator[K] extends Comparator[K] {
    def compare(key1: K, key2: K): Int = {
      val hash1 = hash(key1)
      val hash2 = hash(key2)
      if (hash1 < hash2) -1 else if (hash1 == hash2) 0 else 1
    }
  }
}
