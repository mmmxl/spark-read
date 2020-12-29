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

package org.apache.spark.util.collection

import java.util.Comparator

/**
 * A simple wrapper over the Java implementation [[TimSort]].
 *
 * The Java implementation is package private, and hence it cannot be called outside package
 * org.apache.spark.util.collection. This is a simple wrapper of it that is available to spark.
 * TimSort:
 *  1.扫描数组，确定其中的单调上升段和严格单调下降段，将严格下降段反转；
 *  2.定义最小基本片段长度，短于此的单调片段通过插入排序集中为长于此的段；
 *  3.反复归并一些相邻片段，过程中避免归并长度相差很大的片段，直至整个排序完成，所用分段选择策略可以保证O(n logn)时间复杂性。
 *
 *  插入排序+归并排序
 */
private[spark]
class Sorter[K, Buffer](private val s: SortDataFormat[K, Buffer]) {

  private val timSort = new TimSort(s)

  /**
   * Sorts the input buffer within range [lo, hi).
   */
  def sort(a: Buffer, lo: Int, hi: Int, c: Comparator[_ >: K]): Unit = {
    timSort.sort(a, lo, hi, c)
  }
}
