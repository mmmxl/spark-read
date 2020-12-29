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

package org.apache.spark.unsafe.memory;

import javax.annotation.Nullable;

/**
 * A memory location. Tracked either by a memory address (with off-heap allocation),
 * or by an offset from a JVM object (in-heap allocation).
 * 一个内存位置。通过内存地址（采用堆外分配）或JVM对象的偏移（堆内分配）进行跟踪。
 * obj和offset属性和读写方法
 */
public class MemoryLocation {

  /* 堆内:数据作为对象存储在JVM堆上,此时obj不为空,首先从堆内找到对象,然后使用offset定位数据的具体位置
   * 堆外:存储在堆外内存,此时obj为空,直接用offset属性来在堆外内存中定位数据
   */
  @Nullable
  Object obj;

  long offset;

  /**
   * 构造器
   * @param obj 对象
   * @param offset 偏移量
   */
  public MemoryLocation(@Nullable Object obj, long offset) {
    this.obj = obj;
    this.offset = offset;
  }

  /**
   * 空参构造器
   */
  public MemoryLocation() {
    this(null, 0);
  }

  public void setObjAndOffset(Object newObj, long newOffset) {
    this.obj = newObj;
    this.offset = newOffset;
  }

  public final Object getBaseObject() {
    return obj;
  }

  public final long getBaseOffset() {
    return offset;
  }
}
