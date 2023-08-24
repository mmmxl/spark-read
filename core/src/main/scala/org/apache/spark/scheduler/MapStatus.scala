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

package org.apache.spark.scheduler

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.roaringbitmap.RoaringBitmap

import org.apache.spark.SparkEnv
import org.apache.spark.internal.config
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.Utils

/**
 * Result returned by a ShuffleMapTask to a scheduler. Includes the block manager address that the
 * task ran on as well as the sizes of outputs for each reducer, for passing on to the reduce tasks.
 * 表示ShuffleMapTask返回给TaskScheduler的执行结果
 * 包括任务运行的块管理器地址，以及每个还原器的输出大小，以便传递给还原任务。
 */
private[spark] sealed trait MapStatus {
  /** Location where this task was run.
   * 返回ShuffleMapTask运行的位置,即所在节点的BlockManager的身份标识BlockManagerId
   */
  def location: BlockManagerId

  /**
   * Estimated(估计) size for the reduce block, in bytes.
   *
   * If a block is non-empty, then this method MUST return a non-zero size.  This invariant is
   * necessary for correctness, since block fetchers are allowed to skip zero-size blocks.
   * 返回reduce任务需要拉去的Block的大小(单位为字节)，是一个估计大小
   */
  def getSizeForBlock(reduceId: Int): Long
}


private[spark] object MapStatus {

  /**
   * Min partition number to use [[HighlyCompressedMapStatus]]. A bit ugly here because in test
   * code we can't assume SparkEnv.get exists.
   *
   * spark.shuffle.minNumPartitionsToHighlyCompress [private-2000]
   */
  private lazy val minPartitionsToUseHighlyCompressMapStatus = Option(SparkEnv.get)
    .map(_.conf.get(config.SHUFFLE_MIN_NUM_PARTS_TO_HIGHLY_COMPRESS))
    .getOrElse(config.SHUFFLE_MIN_NUM_PARTS_TO_HIGHLY_COMPRESS.defaultValue.get)

  /**
   * 根据传入的uncompressedSizes决定创建哪一个子类
   * if >minPartitionsToUseHighlyCompressMapStatus(2000) 用HighlyCompressedMapStatus
   * else CompressedMapStatus
   */
  def apply(loc: BlockManagerId, uncompressedSizes: Array[Long]): MapStatus = {
    if (uncompressedSizes.length > minPartitionsToUseHighlyCompressMapStatus) {
      HighlyCompressedMapStatus(loc, uncompressedSizes)
    } else {
      new CompressedMapStatus(loc, uncompressedSizes)
    }
  }

  private[this] val LOG_BASE = 1.1

  /**
   * Compress a size in bytes to 8 bits for efficient reporting of map output sizes.
   * We do this by encoding the log base 1.1 of the size as an integer, which can support
   * sizes up to 35 GB with at most 10% error.
   * long -> byte 将long压缩成byte 压缩比1/8
   */
  def compressSize(size: Long): Byte = {
    if (size == 0) {
      0
    } else if (size <= 1L) {
      1
    } else {
      math.min(255, math.ceil(math.log(size) / math.log(LOG_BASE)).toInt).toByte
    }
  }

  /**
   * Decompress an 8-bit encoded block size, using the reverse operation of compressSize.
   * 解压 byte -> long
   */
  def decompressSize(compressedSize: Byte): Long = {
    if (compressedSize == 0) {
      0
    } else {
      math.pow(LOG_BASE, compressedSize & 0xFF).toLong
    }
  }
}


/**
 * A [[MapStatus]] implementation that tracks the size of each block. Size for each block is
 * represented using a single byte.
 *
 * @param loc location where the task is being executed.
 * @param compressedSizes size of the blocks, indexed by reduce partition id.
 */
private[spark] class CompressedMapStatus(
    private[this] var loc: BlockManagerId,
    private[this] var compressedSizes: Array[Byte])
  extends MapStatus with Externalizable {

  protected def this() = this(null, null.asInstanceOf[Array[Byte]])  // For deserialization only

  /** 这个构造器会做压缩 */
  def this(loc: BlockManagerId, uncompressedSizes: Array[Long]) {
    this(loc, uncompressedSizes.map(MapStatus.compressSize))
  }

  override def location: BlockManagerId = loc

  /** 取出对应的块,并且解压 */
  override def getSizeForBlock(reduceId: Int): Long = {
    MapStatus.decompressSize(compressedSizes(reduceId))
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    loc.writeExternal(out)
    out.writeInt(compressedSizes.length)
    out.write(compressedSizes)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    loc = BlockManagerId(in)
    val len = in.readInt()
    compressedSizes = new Array[Byte](len)
    in.readFully(compressedSizes)
  }
}

/**
 * A [[MapStatus]] implementation that stores the accurate size of huge blocks, which are larger
 * than spark.shuffle.accurateBlockThreshold. It stores the average size of other non-empty blocks,
 * plus a bitmap for tracking which blocks are empty.
 * 一个[[MapStatus]]的实现，它存储巨大块的精确尺寸，这些块大于 spark.shuffle.accurateBlockThreshold
 * 它存储了其他非空块的平均尺寸，加上一个用于跟踪哪些块是空的位图。
 *
 * @param loc location where the task is being executed
 * @param numNonEmptyBlocks the number of non-empty blocks 非空块的数量
 * @param emptyBlocks a bitmap tracking which blocks are empty 一个追踪哪些块是空的位图
 * @param avgSize average size of the non-empty and non-huge blocks 小块的平均大小
 * @param hugeBlockSizes sizes of huge blocks by their reduceId. 巨大块的 大小和reduceId
 */
private[spark] class HighlyCompressedMapStatus private (
    private[this] var loc: BlockManagerId,
    private[this] var numNonEmptyBlocks: Int,
    private[this] var emptyBlocks: RoaringBitmap,
    private[this] var avgSize: Long,
    private var hugeBlockSizes: Map[Int, Byte])
  extends MapStatus with Externalizable {

  // loc could be null when the default constructor is called during deserialization
  require(loc == null || avgSize > 0 || hugeBlockSizes.nonEmpty || numNonEmptyBlocks == 0,
    "Average size can only be zero for map stages that produced no output")

  protected def this() = this(null, -1, null, -1, null)  // For deserialization only

  override def location: BlockManagerId = loc

  /**
   * 1.reduceId在空块的位图内返回0
   * 2.如果是大块就从hugeBlockSizes对应的块大小并解压
   * 3.其他返回avgSize
   * ps: 空块和大块是精准值, 小块是平均值
   */
  override def getSizeForBlock(reduceId: Int): Long = {
    assert(hugeBlockSizes != null)
    if (emptyBlocks.contains(reduceId)) {
      0
    } else {
      hugeBlockSizes.get(reduceId) match {
        case Some(size) => MapStatus.decompressSize(size)
        case None => avgSize
      }
    }
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    loc.writeExternal(out)
    emptyBlocks.writeExternal(out)
    out.writeLong(avgSize)
    out.writeInt(hugeBlockSizes.size)
    hugeBlockSizes.foreach { kv =>
      out.writeInt(kv._1)
      out.writeByte(kv._2)
    }
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    loc = BlockManagerId(in)
    emptyBlocks = new RoaringBitmap()
    emptyBlocks.readExternal(in)
    avgSize = in.readLong()
    val count = in.readInt()
    val hugeBlockSizesArray = mutable.ArrayBuffer[Tuple2[Int, Byte]]()
    (0 until count).foreach { _ =>
      val block = in.readInt()
      val size = in.readByte()
      hugeBlockSizesArray += Tuple2(block, size)
    }
    hugeBlockSizes = hugeBlockSizesArray.toMap
  }
}

private[spark] object HighlyCompressedMapStatus {
  /**
   * 遍历uncompressedSizes
   * 获取spark.shuffle.accurateBlockThreshold的值threshold，默认100m
   * 超过这个值的块就必须精准记录，放置shuffle fetch的时候oom
   * 1.size = 0 加入空块的位图 emptyBlocks
   * 2.size > 0 && size < threshold
   *    判定为小块(small block)，会平均大小
   * 3.size > 0 && size >= threshold
   *    判定为大小(huge block)，记录reduceId和压缩后的block大小
   */
  def apply(loc: BlockManagerId, uncompressedSizes: Array[Long]): HighlyCompressedMapStatus = {
    // We must keep track of which blocks are empty so that we don't report a zero-sized
    // block as being non-empty (or vice-versa) when using the average block size.
    var i = 0
    var numNonEmptyBlocks: Int = 0
    var numSmallBlocks: Int = 0
    var totalSmallBlockSize: Long = 0
    // From a compression standpoint, it shouldn't matter whether we track empty or non-empty
    // blocks. From a performance standpoint, we benefit from tracking empty blocks because
    // we expect that there will be far fewer of them, so we will perform fewer bitmap insertions.
    val emptyBlocks = new RoaringBitmap()
    val totalNumBlocks = uncompressedSizes.length
    // spark.shuffle.accurateBlockThreshold 默认100m
    val threshold = Option(SparkEnv.get)
      .map(_.conf.get(config.SHUFFLE_ACCURATE_BLOCK_THRESHOLD))
      .getOrElse(config.SHUFFLE_ACCURATE_BLOCK_THRESHOLD.defaultValue.get)
    val hugeBlockSizesArray = ArrayBuffer[Tuple2[Int, Byte]]()
    while (i < totalNumBlocks) {
      val size = uncompressedSizes(i)
      if (size > 0) {
        numNonEmptyBlocks += 1
        // Huge blocks are not included in the calculation for average size, thus size for smaller
        // blocks is more accurate.
        if (size < threshold) {
          totalSmallBlockSize += size
          numSmallBlocks += 1
        } else {
          hugeBlockSizesArray += Tuple2(i, MapStatus.compressSize(uncompressedSizes(i)))
        }
      } else {
        emptyBlocks.add(i)
      }
      i += 1
    }
    val avgSize = if (numSmallBlocks > 0) {
      totalSmallBlockSize / numSmallBlocks
    } else {
      0
    }
    emptyBlocks.trim()
    emptyBlocks.runOptimize()
    new HighlyCompressedMapStatus(loc, numNonEmptyBlocks, emptyBlocks, avgSize,
      hugeBlockSizesArray.toMap)
  }
}
