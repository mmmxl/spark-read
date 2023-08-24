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

import java.io._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.ObjectWritable
import org.apache.hadoop.io.Writable

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils

/**
 * 继承自hadoop的Writable接口
 * 实现的序列化的写功能
 */
@DeveloperApi
class SerializableWritable[T <: Writable](@transient var t: T) extends Serializable {

  def value: T = t

  override def toString: String = t.toString

  /**
   * 1.读取非static和transient字段到流中
   * 2.transient修饰的t写入out流中
   */
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {
    // 将当前类的非静态和非瞬时字段写到这个流中
    // 这只能从被序列化的类的writeObject方法中调用。如果以其他方式调用，它将抛出NotActiveException
    out.defaultWriteObject()
    new ObjectWritable(t).write(out)
  }

  /**
   * 1.从流中读取当前类的非static和transient字段
   * 2.创建ObjectWritable对象
   * 3.读取字段
   */
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    // 从这个流中读取当前类的非静态和非瞬时字段
    // 这只能从被反序列化的类的readObject方法中调用。如果以其他方式调用，它将抛出NotActiveException。
    in.defaultReadObject()
    val ow = new ObjectWritable()
    ow.setConf(new Configuration(false))
    ow.readFields(in)
    t = ow.get().asInstanceOf[T]
  }
}
