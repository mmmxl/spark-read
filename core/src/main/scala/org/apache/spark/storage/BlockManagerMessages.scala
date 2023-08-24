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

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.util.Utils

private[spark] object BlockManagerMessages {
  //////////////////////////////////////////////////////////////////////////////////
  // Messages from the master to slaves.
  // 发送给BlockManagerSlave的消息
  //////////////////////////////////////////////////////////////////////////////////
  sealed trait ToBlockManagerSlave

  // Remove a block from the slaves that have it. This can only be used to remove
  // blocks that the master knows about.
  // 移除Block
  case class RemoveBlock(blockId: BlockId) extends ToBlockManagerSlave

  // Replicate blocks that were lost due to executor failure
  //
  case class ReplicateBlock(blockId: BlockId, replicas: Seq[BlockManagerId], maxReplicas: Int)
    extends ToBlockManagerSlave

  // Remove all blocks belonging to a specific RDD.
  case class RemoveRdd(rddId: Int) extends ToBlockManagerSlave

  // Remove all blocks belonging to a specific shuffle.
  case class RemoveShuffle(shuffleId: Int) extends ToBlockManagerSlave

  // Remove all blocks belonging to a specific broadcast.
  case class RemoveBroadcast(broadcastId: Long, removeFromDriver: Boolean = true)
    extends ToBlockManagerSlave

  /**
   * Driver to Executor message to trigger a thread dump.
   */
  case object TriggerThreadDump extends ToBlockManagerSlave

  //////////////////////////////////////////////////////////////////////////////////
  // Messages from slaves to the master.
  // 发送给BlockManagerMaster的消息
  //////////////////////////////////////////////////////////////////////////////////
  sealed trait ToBlockManagerMaster

  // 注册BlockManager
  case class RegisterBlockManager(
      blockManagerId: BlockManagerId,
      maxOnHeapMemSize: Long,
      maxOffHeapMemSize: Long,
      sender: RpcEndpointRef)
    extends ToBlockManagerMaster

  // 更新Block信息
  case class UpdateBlockInfo(
      var blockManagerId: BlockManagerId,
      var blockId: BlockId,
      var storageLevel: StorageLevel,
      var memSize: Long,
      var diskSize: Long)
    extends ToBlockManagerMaster
    with Externalizable {

    def this() = this(null, null, null, 0, 0)  // For deserialization only

    override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
      blockManagerId.writeExternal(out)
      out.writeUTF(blockId.name)
      storageLevel.writeExternal(out)
      out.writeLong(memSize)
      out.writeLong(diskSize)
    }

    override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
      blockManagerId = BlockManagerId(in)
      blockId = BlockId(in.readUTF())
      storageLevel = StorageLevel(in)
      memSize = in.readLong()
      diskSize = in.readLong()
    }
  }

  // 获取Block的位置
  case class GetLocations(blockId: BlockId) extends ToBlockManagerMaster

  // 获取Block的位置和Status
  case class GetLocationsAndStatus(blockId: BlockId) extends ToBlockManagerMaster

  // The response message of `GetLocationsAndStatus` request.
  // GetLocationsAndStatus的响应信息
  case class BlockLocationsAndStatus(locations: Seq[BlockManagerId], status: BlockStatus) {
    assert(locations.nonEmpty)
  }

  // 获取多个Block的位置
  case class GetLocationsMultipleBlockIds(blockIds: Array[BlockId]) extends ToBlockManagerMaster

  // 获取其他BlockManager的BlockManagerId
  case class GetPeers(blockManagerId: BlockManagerId) extends ToBlockManagerMaster
  // 获取Executor的EndpointRef引用
  case class GetExecutorEndpointRef(executorId: String) extends ToBlockManagerMaster
  // 移除Block
  case class RemoveExecutor(execId: String) extends ToBlockManagerMaster

  case object StopBlockManagerMaster extends ToBlockManagerMaster
  // 获取指定的BlockManager的内存状态
  case object GetMemoryStatus extends ToBlockManagerMaster
  //
  case object GetStorageStatus extends ToBlockManagerMaster
  // 获取Block的状态
  case class GetBlockStatus(blockId: BlockId, askSlaves: Boolean = true)
    extends ToBlockManagerMaster
  // 获取匹配过滤条件的Block
  case class GetMatchingBlockIds(filter: BlockId => Boolean, askSlaves: Boolean = true)
    extends ToBlockManagerMaster
  // BlockManager的心跳信息
  case class BlockManagerHeartbeat(blockManagerId: BlockManagerId) extends ToBlockManagerMaster
  // 指定的Executor上是否有缓存的Block
  case class HasCachedBlocks(executorId: String) extends ToBlockManagerMaster
}
