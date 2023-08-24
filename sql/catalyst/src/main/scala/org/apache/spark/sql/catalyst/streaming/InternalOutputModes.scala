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

package org.apache.spark.sql.catalyst.streaming

import java.util.Locale

import org.apache.spark.sql.streaming.OutputMode

/**
 * Internal helper class to generate objects representing various `OutputMode`s,
 */
private[sql] object InternalOutputModes {

  /**
   * OutputMode in which only the new rows in the streaming DataFrame/Dataset will be
   * written to the sink. This output mode can be only be used in queries that do not
   * contain any aggregation.
   * 新行才会被写入sink，这种输出模式只能用于不包含任何聚合的查询
   */
  case object Append extends OutputMode

  /**
   * OutputMode in which all the rows in the streaming DataFrame/Dataset will be written
   * to the sink every time these is some updates. This output mode can only be used in queries
   * that contain aggregations.
   * 更新时，所有行都将被写入sink。这种输出模式只能用于包含聚合的查询中。
   */
  case object Complete extends OutputMode

  /**
   * OutputMode in which only the rows in the streaming DataFrame/Dataset that were updated will be
   * written to the sink every time these is some updates. If the query doesn't contain
   * aggregations, it will be equivalent to `Append` mode.
   * 被更新的行会被写入sink。如果查询不包含聚合，则相当于 "Append "模式。
   */
  case object Update extends OutputMode


  def apply(outputMode: String): OutputMode = {
    outputMode.toLowerCase(Locale.ROOT) match {
      case "append" =>
        OutputMode.Append
      case "complete" =>
        OutputMode.Complete
      case "update" =>
        OutputMode.Update
      case _ =>
        throw new IllegalArgumentException(s"Unknown output mode $outputMode. " +
          "Accepted output modes are 'append', 'complete', 'update'")
    }
  }
}
