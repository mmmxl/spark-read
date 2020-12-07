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

package org.apache.spark.ui

import java.util.{Date, List => JList, ServiceLoader}

import scala.collection.JavaConverters._

import org.apache.spark.{JobExecutionStatus, SecurityManager, SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler._
import org.apache.spark.status.AppStatusStore
import org.apache.spark.status.api.v1._
import org.apache.spark.ui.JettyUtils._
import org.apache.spark.ui.env.EnvironmentTab
import org.apache.spark.ui.exec.ExecutorsTab
import org.apache.spark.ui.jobs.{JobsTab, StagesTab}
import org.apache.spark.ui.storage.StorageTab
import org.apache.spark.util.Utils

/**
 * Top level user interface for a Spark application.
 * Spark应用程序的顶级用户接口
 * WebUI:网页UI基类，UI的基本方法
 * Logging:日志打印功能
 * UIRoot:提供了暴露应用信息为json的接口
 */
private[spark] class SparkUI private (
    val store: AppStatusStore,
    val sc: Option[SparkContext],
    val conf: SparkConf,
    securityManager: SecurityManager,
    var appName: String,
    val basePath: String,
    val startTime: Long,
    val appSparkVersion: String)
  extends WebUI(securityManager, securityManager.getSSLOptions("ui"), SparkUI.getUIPort(conf),
    conf, basePath, "SparkUI")
  with Logging
  with UIRoot {
  // 是否提供杀死Stage或者Job的连接
  val killEnabled = sc.map(_.conf.getBoolean("spark.ui.killEnabled", true)).getOrElse(false)

  var appId: String = _

  // 流程序监听器 SparkStreaming  SparkStructStreaming用
  private var streamingJobProgressListener: Option[SparkListener] = None

  /** Initialize all components of the server.
   * 初始化服务的所有组件
   */
  def initialize(): Unit = {
    // 1.构造页面布局比哦那个给每个WebUITab中的所有WebUIPage创建对应的ServletContextHandler
    val jobsTab = new JobsTab(this, store)
    attachTab(jobsTab)
    val stagesTab = new StagesTab(this, store)
    attachTab(stagesTab)
    attachTab(new StorageTab(this, store))
    attachTab(new EnvironmentTab(this, store))
    attachTab(new ExecutorsTab(this))
    // 2.调用JettyUtils的createRedirectHandler方法创建对静态目录`org/apache/spark/ui/static`提供文件服务的ServletContextHandler
    addStaticHandler(SparkUI.STATIC_RESOURCE_DIR)
    // 3.调用JettyUtils的createRedirectHandler方法创建几个将用户对源路径的请求重定向到目标路径的ServletContextHandler
    //   e.g. 用户对根路径"/"的请求重定向到目标木锯"/jobs/"的ServletContextHandler
    attachHandler(createRedirectHandler("/", "/jobs/", basePath = basePath))
    attachHandler(ApiRootResource.getServletHandler(this))

    // These should be POST only, but, the YARN AM proxy won't proxy POSTs
    attachHandler(createRedirectHandler(
      "/jobs/job/kill", "/jobs/", jobsTab.handleKillRequest, httpMethods = Set("GET", "POST")))
    attachHandler(createRedirectHandler(
      "/stages/stage/kill", "/stages/", stagesTab.handleKillRequest,
      httpMethods = Set("GET", "POST")))
  }

  initialize() // 初始化

  // Spark用户
  def getSparkUser: String = {
    try {
      Option(store.applicationInfo().attempts.head.sparkUser)
        .orElse(store.environmentInfo().systemProperties.toMap.get("user.name"))
        .getOrElse("<unknown>")
    } catch {
      case _: NoSuchElementException => "<unknown>"
    }
  }

  // 得到appName
  def getAppName: String = appName

  // 设置appId
  def setAppId(id: String): Unit = {
    appId = id
  }

  /** Stop the server behind this web interface. Only valid after bind().
   *  停止Jetty服务
   */
  override def stop() {
    super.stop()
    logInfo(s"Stopped Spark web UI at $webUrl")
  }

  // 柯里化，根据appId获取SparkUI并且得到一个参数为 Spark=>T 函数的函数
  override def withSparkUI[T](appId: String, attemptId: Option[String])(fn: SparkUI => T): T = {
    if (appId == this.appId) {
      fn(this)
    } else {
      throw new NoSuchElementException()
    }
  }

  // 获取应用信息的list
  def getApplicationInfoList: Iterator[ApplicationInfo] = {
    Iterator(new ApplicationInfo(
      id = appId,
      name = appName,
      coresGranted = None,
      maxCores = None,
      coresPerExecutor = None,
      memoryPerExecutorMB = None,
      attempts = Seq(new ApplicationAttemptInfo(
        attemptId = None,
        startTime = new Date(startTime),
        endTime = new Date(-1),
        duration = 0,
        lastUpdated = new Date(startTime),
        sparkUser = getSparkUser,
        completed = false,
        appSparkVersion = appSparkVersion
      ))
    ))
  }

  // 得到对应ApplicationId对应的应用信息
  def getApplicationInfo(appId: String): Option[ApplicationInfo] = {
    getApplicationInfoList.find(_.id == appId)
  }

  // 得到流程序监听器 SparkStreaming  SparkStructStreaming用
  def getStreamingJobProgressListener: Option[SparkListener] = streamingJobProgressListener

  // 设置流程序监听器 SparkStreaming  SparkStructStreaming用
  def setStreamingJobProgressListener(sparkListener: SparkListener): Unit = {
    streamingJobProgressListener = Option(sparkListener)
  }

  // 清除流程序监听器 SparkStreaming  SparkStructStreaming用
  def clearStreamingJobProgressListener(): Unit = {
    streamingJobProgressListener = None
  }
}

private[spark] abstract class SparkUITab(parent: SparkUI, prefix: String)
  extends WebUITab(parent, prefix) {

  def appName: String = parent.appName

  def appSparkVersion: String = parent.appSparkVersion
}

private[spark] object SparkUI {
  val DEFAULT_PORT = 4040
  val STATIC_RESOURCE_DIR = "org/apache/spark/ui/static"
  val DEFAULT_POOL_NAME = "default"

  def getUIPort(conf: SparkConf): Int = {
    conf.getInt("spark.ui.port", SparkUI.DEFAULT_PORT)
  }

  /**
   * Create a new UI backed by an AppStatusStore.
   */
  def create(
      sc: Option[SparkContext],
      store: AppStatusStore,
      conf: SparkConf,
      securityManager: SecurityManager,
      appName: String,
      basePath: String,
      startTime: Long,
      appSparkVersion: String = org.apache.spark.SPARK_VERSION): SparkUI = {

    new SparkUI(store, sc, conf, securityManager, appName, basePath, startTime, appSparkVersion)
  }

}
