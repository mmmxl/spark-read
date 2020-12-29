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

import org.apache.spark.annotation.{DeveloperApi, InterfaceStability}
import org.apache.spark.sql.execution.SparkStrategy

/**
 * Allows the execution of relational queries, including those expressed in SQL using Spark.
 * 允许执行关系型查询，包括那些使用Spark的SQL表达的查询。
 *
 *  @groupname dataType Data types
 *  @groupdesc Spark SQL data types.
 *  @groupprio dataType -3
 *  @groupname field Field
 *  @groupprio field -2
 *  @groupname row Row
 *  @groupprio row -1
 */
package object sql {

  /**
   * Converts a logical plan into zero or more SparkPlans.  This API is exposed for experimenting
   * with the query planner and is not designed to be stable across spark releases.  Developers
   * writing libraries should instead consider using the stable APIs provided in
   * [[org.apache.spark.sql.sources]]
   * 将一个逻辑计划转换为零或多个SparkPlans
   * 这个API的暴露是为了实验查询计划器，而不是为了在不同的spark版本中保持稳定。
   * 开发者在编写库时应该考虑使用在[[org.apache.spark.sql.sources]]
   */
  @DeveloperApi
  @InterfaceStability.Unstable
  type  Strategy  = SparkStrategy

  type DataFrame = Dataset[Row]
}
