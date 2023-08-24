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

package org.apache.spark.sql.catalyst.expressions

import scala.reflect.internal.util.AbstractFileClassLoader
import org.apache.spark.sql.catalyst.rules
import org.apache.spark.util.Utils

import java.io.File

/**
 * A collection of generators that build custom bytecode at runtime for performing the evaluation
 * of catalyst expression.
 * 一组生成器，它们在运行时构建自定义字节码，以执行catalyst表达式的评估
 */
package object codegen {

  /** Canonicalizes an expression so those that differ only by names can reuse the same code.
   * 规范化表达式，以便那些仅名称不同的表达式可以重用相同的代码。
   */
  object ExpressionCanonicalizer extends rules.RuleExecutor[Expression] {
    val batches =
      Batch("CleanExpressions", FixedPoint(20), CleanExpressions) :: Nil

    // 去除别名，使不同的表达式可以重用
    object CleanExpressions extends rules.Rule[Expression] {
      def apply(e: Expression): Expression = e transform {
        case Alias(c, _) => c
      }
    }
  }

  /**
   * Dumps the bytecode from a class to the screen using javap.
   * 使用 javap 将字节码从类转储到屏幕
   */
  object DumpByteCode {
    import scala.sys.process._
    val dumpDirectory: File = Utils.createTempDir()
    dumpDirectory.mkdir()

    def apply(obj: Any): Unit = {
      val generatedClass = obj.getClass
      // 加载文件的类加载器
      val classLoader =
        generatedClass
          .getClassLoader
          .asInstanceOf[AbstractFileClassLoader]
      // 类文件的实际字节数，如果找不到则为空数组。
      val generatedBytes = classLoader.classBytes(generatedClass.getName)

      val packageDir = new java.io.File(dumpDirectory, generatedClass.getPackage.getName)
      if (!packageDir.exists()) { packageDir.mkdir() }

      val classFile =
        new java.io.File(packageDir, generatedClass.getName.split("\\.").last + ".class")

      val outfile = new java.io.FileOutputStream(classFile)
      outfile.write(generatedBytes)
      outfile.close()

      // scalastyle:off println
      // !!: shell执行命令，堵塞直至返回结果，返回码非0就抛出异常
      // javap是JDK自带的反汇编器，可以查看java编译器为我们生成的字节码
      // -p 显示所有类和成员 -v 输出附加信息
      println(
        s"javap -p -v -classpath ${dumpDirectory.getCanonicalPath} ${generatedClass.getName}".!!)
      // scalastyle:on println
    }
  }
}
