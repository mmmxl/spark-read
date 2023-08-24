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
package org.apache.spark.rpc.netty

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer}
import org.apache.spark.network.server.StreamManager
import org.apache.spark.rpc.RpcEnvFileServer
import org.apache.spark.util.Utils

/**
 * StreamManager implementation for serving files from a NettyRpcEnv.
 *
 * Three kinds of resources can be registered in this manager, all backed by actual files:
 *
 * - "/files": a flat list of files; used as the backend for [[SparkContext.addFile]].
 * - "/jars": a flat list of files; used as the backend for [[SparkContext.addJar]].
 * - arbitrary directories; all files under the directory become available through the manager,
 *   respecting the directory's hierarchy.
 *
 * Only streaming (openStream) is supported.
 *
 * StreamManager实现了从NettyRpcEnv中服务文件。
 * 在这个管理器中可以注册三种资源，所有资源都有实际的文件支持。
 * - "/files": 一个扁平的文件列表; 用作[[SparkContext.addFile]]的后端。
 *  - "/jars"：一个扁平的文件列表；作为[[SparkContext.addJar]]的后端使用。
 * - 任意目录；该目录下的所有文件都可以通过管理器获得，尊重该目录的层次结构。
 * 只支持流（openStream）。
 */
private[netty] class NettyStreamManager(rpcEnv: NettyRpcEnv)
  extends StreamManager with RpcEnvFileServer {

  private val files = new ConcurrentHashMap[String, File]()
  private val jars = new ConcurrentHashMap[String, File]()
  private val dirs = new ConcurrentHashMap[String, File]()

  override def getChunk(streamId: Long, chunkIndex: Int): ManagedBuffer = {
    throw new UnsupportedOperationException()
  }

  /**
   * 从缓存中获取文件后,将transportConf及File等信息封装成FileSegmentManagedBuffer返回
   */
  override def openStream(streamId: String): ManagedBuffer = {
    val Array(ftype, fname) = streamId.stripPrefix("/").split("/", 2)
    val file = ftype match {
      case "files" => files.get(fname)
      case "jars" => jars.get(fname)
      case other =>
        val dir = dirs.get(ftype)
        require(dir != null, s"Invalid stream URI: $ftype not found.")
        new File(dir, fname)
    }

    if (file != null && file.isFile()) {
      new FileSegmentManagedBuffer(rpcEnv.transportConf, file, 0, file.length())
    } else {
      null
    }
  }

  override def addFile(file: File): String = {
    val existingPath = files.putIfAbsent(file.getName, file)
    // 如果文件不存在或者文件名相同但实际文件不同 则报错
    require(existingPath == null || existingPath == file,
      s"File ${file.getName} was already registered with a different path " +
        s"(old path = $existingPath, new path = $file")
    s"${rpcEnv.address.toSparkURL}/files/${Utils.encodeFileNameToURIRawPath(file.getName())}"
  }

  override def addJar(file: File): String = {
    val existingPath = jars.putIfAbsent(file.getName, file)
    require(existingPath == null || existingPath == file,
      s"File ${file.getName} was already registered with a different path " +
        s"(old path = $existingPath, new path = $file")
    s"${rpcEnv.address.toSparkURL}/jars/${Utils.encodeFileNameToURIRawPath(file.getName())}"
  }

  override def addDirectory(baseUri: String, path: File): String = {
    val fixedBaseUri = validateDirectoryUri(baseUri)
    require(dirs.putIfAbsent(fixedBaseUri.stripPrefix("/"), path) == null,
      s"URI '$fixedBaseUri' already registered.")
    s"${rpcEnv.address.toSparkURL}$fixedBaseUri"
  }

}
