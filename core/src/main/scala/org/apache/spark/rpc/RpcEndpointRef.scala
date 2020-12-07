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

package org.apache.spark.rpc

import scala.concurrent.Future
import scala.reflect.ClassTag

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.util.RpcUtils

/**
 * A reference for a remote [[RpcEndpoint]]. [[RpcEndpointRef]] is thread-safe.
 * RpcEndPoint的一个远程引用，是线程安全的
 */
private[spark] abstract class RpcEndpointRef(conf: SparkConf)
  extends Serializable with Logging {
  /* Rpc最大重新连接次数 spark.rpc.numRetries 默认3 */
  private[this] val maxRetries = RpcUtils.numRetries(conf)
  /* Rpc每次重新连接需要等待的毫秒数 spark.rpc.retry.wait 默认3 */
  private[this] val retryWaitMs = RpcUtils.retryWaitMs(conf)
  /* Rpc的ask操作的默认超时时间 spark.rpc.askTimeOut/spark.network.timeout 默认120s */
  private[this] val defaultAskTimeout = RpcUtils.askRpcTimeout(conf)

  /**
   * return the address for the [[RpcEndpointRef]]
   * 返回当前RpcEndPointRef对应RpcEndPoint的Rpc地址(RpcAddress)
   */
  def address: RpcAddress
  /* 返回当前RpcEndPointRef对应的RpcEndPoint的名称 */
  def name: String

  /**
   * Sends a one-way asynchronous message. Fire-and-forget semantics.
   * 发送单向异步的消息。at-most-once的投递规则，类似Akka的tell()
   * "单向"(one-way):发送完后就会忘记此次发送，不会有任何状态要记录，也不会期望得到服务端的回复
   */
  def send(message: Any): Unit

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
   * receive the reply within the specified timeout.
   *
   * This method only sends the message once and never retries.
   * 以默认的超时时间作为timeout参数，调用ask，期望收到服务端的回复 at-most-once的投递规则
   */
  def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T]

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
   * receive the reply within a default timeout.
   *
   * This method only sends the message once and never retries.
   */
  def ask[T: ClassTag](message: Any): Future[T] = ask(message, defaultAskTimeout)

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
   * default timeout, throw an exception if this fails.
   *
   * Note: this is a blocking action which may cost a lot of time,  so don't call it in a message
   * loop of [[RpcEndpoint]].
   * @param message the message to send
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  def askSync[T: ClassTag](message: Any): T = askSync(message, defaultAskTimeout)

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
   * specified timeout, throw an exception if this fails.
   *
   * Note: this is a blocking action which may cost a lot of time, so don't call it in a message
   * loop of [[RpcEndpoint]].
   *
   * @param message the message to send
   * @param timeout the timeout duration
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   * 一个同步的ask()方法，超时抛出SparkException，会重试，直到超过重试次数
   * 因为重试，服务端可能收到多条消息，所以要求服务端的处理是幂等的
   * at-least-once的投递原则
   */
  def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
    val future = ask[T](message, timeout)
    timeout.awaitResult(future)
  }

}
