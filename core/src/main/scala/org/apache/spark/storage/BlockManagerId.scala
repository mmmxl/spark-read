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

package org.apache.spark.storage

import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}

import com.google.common.cache.{CacheBuilder, CacheLoader}

import org.apache.spark.SparkContext
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 * This class represent a unique identifier for a BlockManager.
 *
 * The first 2 constructors of this class are made private to ensure that BlockManagerId objects
 * can be created only using the apply method in the companion object. This allows de-duplication
 * of ID objects. Also, constructor parameters are private to ensure that parameters cannot be
 * modified from outside this class.
 * 这个类的前两个构造函数是私有的，以确保BlockManagerId对象只能使用同伴对象中的apply方法创建。
 * 这允许ID对象的去重复化。另外，构造函数参数是私有的，以确保参数不能从这个类之外被修改。
 *
 * Externalizable是Serializable的子接口，由我们来指定需要序列化的内容
 */
@DeveloperApi
class BlockManagerId private (
    // 当前BlockManager所在的实例的Id,Driver为driver
    // Executor由Master负责分配，ID格式为app -日期格式字符串 - 数字
    private var executorId_ : String,
    private var host_ : String, // 主机域名或IP
    private var port_ : Int, // BlockManager中的BlockTransferService对外服务的端口
    private var topologyInfo_ : Option[String] /* 拓扑信息 */)
  extends Externalizable {

  private def this() = this(null, null, 0, None)  // For deserialization only

  def executorId: String = executorId_
  // 参数检查
  if (null != host_) {
    Utils.checkHost(host_)
    assert (port_ > 0)
  }

  def hostPort: String = {
    // DEBUG code
    Utils.checkHost(host)
    assert (port > 0)
    host + ":" + port
  }

  def host: String = host_

  def port: Int = port_

  def topologyInfo: Option[String] = topologyInfo_

  def isDriver: Boolean = {
    executorId == SparkContext.DRIVER_IDENTIFIER ||
      executorId == SparkContext.LEGACY_DRIVER_IDENTIFIER
  }

  /** 序列化  */
  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    out.writeUTF(executorId_)
    out.writeUTF(host_)
    out.writeInt(port_)
    out.writeBoolean(topologyInfo_.isDefined)
    // we only write topologyInfo if we have it
    topologyInfo.foreach(out.writeUTF(_))
  }

  /** 反序列化  */
  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    executorId_ = in.readUTF()
    host_ = in.readUTF()
    port_ = in.readInt()
    val isTopologyInfoAvailable = in.readBoolean()
    topologyInfo_ = if (isTopologyInfoAvailable) Option(in.readUTF()) else None
  }

  @throws(classOf[IOException])
  private def readResolve(): Object = BlockManagerId.getCachedBlockManagerId(this)

  override def toString: String = s"BlockManagerId($executorId, $host, $port, $topologyInfo)"

  override def hashCode: Int =
    ((executorId.hashCode * 41 + host.hashCode) * 41 + port) * 41 + topologyInfo.hashCode

  override def equals(that: Any): Boolean = that match {
    case id: BlockManagerId =>
      executorId == id.executorId &&
        port == id.port &&
        host == id.host &&
        topologyInfo == id.topologyInfo
    case _ =>
      false
  }
}


private[spark] object BlockManagerId {

  /**
   * Returns a [[org.apache.spark.storage.BlockManagerId]] for the given configuration.
   *
   * @param execId ID of the executor.
   * @param host Host name of the block manager.
   * @param port Port of the block manager.
   * @param topologyInfo topology information for the blockmanager, if available
   *                     This can be network topology information for use while choosing peers
   *                     while replicating data blocks. More information available here:
   *                     [[org.apache.spark.storage.TopologyMapper]]
   * @return A new [[org.apache.spark.storage.BlockManagerId]].
   */
  def apply(
      execId: String,
      host: String,
      port: Int,
      topologyInfo: Option[String] = None): BlockManagerId =
    getCachedBlockManagerId(new BlockManagerId(execId, host, port, topologyInfo))

  /** 根据一个对象输入流反序列化 */
  def apply(in: ObjectInput): BlockManagerId = {
    val obj = new BlockManagerId()
    obj.readExternal(in)
    getCachedBlockManagerId(obj)
  }

  /**
   * The max cache size is hardcoded(硬编码) to 10000, since the size of a BlockManagerId
   * object is about 48B, the total memory cost should be below 1MB which is feasible.
   */
  val blockManagerIdCache = CacheBuilder.newBuilder()
    .maximumSize(10000)
    .build(new CacheLoader[BlockManagerId, BlockManagerId]() {
      override def load(id: BlockManagerId) = id
    })

  def getCachedBlockManagerId(id: BlockManagerId): BlockManagerId = {
    blockManagerIdCache.get(id)
  }
}
