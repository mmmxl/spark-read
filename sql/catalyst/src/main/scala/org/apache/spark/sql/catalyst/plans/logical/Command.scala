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

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.Attribute

/**
 * a logical node that represents a non-query command to be executed by the system.  for example,
 * commands can be used by parsers to represent ddl operations.  commands, unlike queries, are
 * eagerly executed.
 * 非查询操作，例如ddl操作，命令是立即执行，非惰性的
 */
trait Command extends LogicalPlan {
  override def output: Seq[Attribute] = Seq.empty
  override def children: Seq[LogicalPlan] = Seq.empty
}
