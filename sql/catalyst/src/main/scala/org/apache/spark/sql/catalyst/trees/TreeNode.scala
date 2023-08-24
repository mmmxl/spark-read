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

package org.apache.spark.sql.catalyst.trees

import java.util.UUID

import scala.collection.Map
import scala.reflect.ClassTag

import org.apache.commons.lang3.ClassUtils
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.sql.catalyst.IdentifierWithDatabase
import org.apache.spark.sql.catalyst.ScalaReflection._
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogStorageFormat, CatalogTable, CatalogTableType, FunctionResource}
import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.physical.{BroadcastMode, Partitioning}
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

/** Used by [[TreeNode.getNodeNumbered]] when traversing(遍历) the tree for a given number */
private class MutableInt(var i: Int)

/** line：行数 startPosition：字符起始位置  */
case class Origin(
  line: Option[Int] = None,
  startPosition: Option[Int] = None)

/**
 * Provides a location for TreeNodes to ask about the context of their origin.  For example, which
 * line of code is currently being parsed.
 * 为TreeNodes提供了一个位置，以询问其起源的上下文。 例如，当前正在解析哪一行代码。
 */
object CurrentOrigin {
  // 一个线程本地的值
  private val value = new ThreadLocal[Origin]() {
    override def initialValue: Origin = Origin()
  }

  def get: Origin = value.get()
  def set(o: Origin): Unit = value.set(o)
  // 重置
  def reset(): Unit = value.set(Origin())
  // 对于样例类的属性修改,databricks建议的是采用这种copy的方式
  def setPosition(line: Int, start: Int): Unit = {
    value.set(
      value.get.copy(line = Some(line), startPosition = Some(start)))
  }

  /**
   * 1.先设置origin
   * 2.执行传入的传名函数,执行结束,重置value
   * 3.返回传入的f结果
   */
  def withOrigin[A](o: Origin)(f: => A): A = {
    set(o)
    val ret = try f finally { reset() }
    ret
  }
}

// scalastyle:off
abstract class TreeNode[BaseType <: TreeNode[BaseType]] extends Product {
// scalastyle:on
  // 自我类型,这里把自己看作BaseType类型,this的类型就是BaseType
  self: BaseType =>
  // 当前所在代码位置
  val origin: Origin = CurrentOrigin.get

  /**
   * Returns a Seq of the children of this node.
   * Children should not change. Immutability required for containsChild optimization
   * 返回这个节点的子节点的Seq。子节点不应改变。包含子节点的优化需要有不可更改性
   */
  def children: Seq[BaseType]

  lazy val containsChild: Set[TreeNode[_]] = children.toSet

  // 可以看出这里hashCode用的murmurHash3
  private lazy val _hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)
  override def hashCode(): Int = _hashCode

  /**
   * Faster version of equality which short-circuits when two treeNodes are the same instance.
   * We don't just override Object.equals, as doing so prevents the scala compiler from
   * generating case class `equals` methods
   *
   * 当两个 treeNodes 是相同的实例时，会短路的更快版本的equals。
   * 我们不只是覆盖Object.equals，因为这样做可以防止scala编译器生成case类`equals`方法。
   * 先引用比较再值比较
   * ps: [_]是一个类型构造函数
   *     TreeNode[_]:具有类型构造函数的类型
   */
  def fastEquals(other: TreeNode[_]): Boolean = {
    this.eq(other) || this == other
  }

  /**
   * Find the first [[TreeNode]] that satisfies the condition specified by `f`.
   * The condition is recursively applied to this node and all of its children (pre-order).
   * 寻找第一个符合条件的结点
   */
  def find(f: BaseType => Boolean): Option[BaseType] = if (f(this)) {
    Some(this)
  } else {
    children.foldLeft(Option.empty[BaseType]) { (l, r) => l.orElse(r.find(f)) }
  }

  /**
   * Runs the given function on this node and then recursively on [[children]].
   * 自顶向下遍历所有结点执行函数f
   * @param f the function to be applied to each node in the tree.
   */
  def foreach(f: BaseType => Unit): Unit = {
    f(this)
    children.foreach(_.foreach(f))
  }

  /**
   * Runs the given function recursively on [[children]] then on this node.
   * 自底向上遍历所有结点执行函数f
   * @param f the function to be applied to each node in the tree.
   */
  def foreachUp(f: BaseType => Unit): Unit = {
    children.foreach(_.foreachUp(f))
    f(this)
  }

  /**
   * Returns a Seq containing the result of applying the given function to each
   * node in this tree in a preorder traversal.
   * 自顶向下的遍历结点并将其用f转换的结果放入ArrayBuffer中,最终返回ArrayBuffer
   * @param f the function to be applied.
   */
  def map[A](f: BaseType => A): Seq[A] = {
    val ret = new collection.mutable.ArrayBuffer[A]()
    foreach(ret += f(_))
    ret
  }

  /**
   * Returns a Seq by applying a function to all nodes in this tree and using the elements of the
   * resulting collections.
   */
  def flatMap[A](f: BaseType => TraversableOnce[A]): Seq[A] = {
    val ret = new collection.mutable.ArrayBuffer[A]()
    foreach(ret ++= f(_))
    ret
  }

  /**
   * Returns a Seq containing the result of applying a partial function to all elements in this
   * tree on which the function is defined.
   * 如果该偏函数被定义了，返回一个Seq，其中包含对该树中所有元素应用偏函数的结果
   */
  def collect[B](pf: PartialFunction[BaseType, B]): Seq[B] = {
    val ret = new collection.mutable.ArrayBuffer[B]()
    val lifted = pf.lift
    foreach(node => lifted(node).foreach(ret.+=))
    ret
  }

  /**
   * Returns a Seq containing the leaves in this tree.
   * 返回包含所有叶子的Seq
   */
  def collectLeaves(): Seq[BaseType] = {
    this.collect { case p if p.children.isEmpty => p }
  }

  /**
   * Finds and returns the first [[TreeNode]] of the tree for which the given partial function
   * is defined (pre-order), and applies the partial function to it.
   * 查找并返回定义了给定偏函数的树的第一个结点，并对它使用偏函数。
   */
  def collectFirst[B](pf: PartialFunction[BaseType, B]): Option[B] = {
    val lifted = pf.lift
    lifted(this).orElse {
      children.foldLeft(Option.empty[B]) { (l, r) => l.orElse(r.collectFirst(pf)) }
    }
  }

  /**
   * Efficient alternative to `productIterator.map(f).toArray`.
   * 有效替代`productIterator.map(f).toArray`(为了性能)
   * 打印出类的构造器参数
   */
  protected def mapProductIterator[B: ClassTag](f: Any => B): Array[B] = {
    val arr = Array.ofDim[B](productArity)
    var i = 0
    while (i < arr.length) {
      arr(i) = f(productElement(i))
      i += 1
    }
    arr
  }

  /**
   * Returns a copy of this node with the children replaced.
   * 用newChildren替换当前结点中的孩子结点,返回副本
   * TODO: Validate somewhere (in debug mode?) that children are ordered correctly.
   */
  def withNewChildren(newChildren: Seq[BaseType]): BaseType = {
    assert(newChildren.size == children.size, "Incorrect number of children")
    var changed = false
    val remainingNewChildren = newChildren.toBuffer
    val remainingOldChildren = children.toBuffer
    def mapTreeNode(node: TreeNode[_]): TreeNode[_] = {
      val newChild = remainingNewChildren.remove(0)
      val oldChild = remainingOldChildren.remove(0)
      if (newChild fastEquals oldChild) {
        oldChild
      } else {
        changed = true
        newChild
      }
    }
    def mapChild(child: Any): Any = child match {
      case arg: TreeNode[_] if containsChild(arg) => mapTreeNode(arg)
      // CaseWhen Case or any tuple type
      case (left, right) => (mapChild(left), mapChild(right))
      case nonChild: AnyRef => nonChild
      case null => null
    }
    val newArgs = mapProductIterator {
      case s: StructType => s // Don't convert struct types to some other type of Seq[StructField]
      // Handle Seq[TreeNode] in TreeNode parameters.
      case s: Stream[_] =>
        // Stream is lazy so we need to force materialization
        s.map(mapChild).force
      case s: Seq[_] =>
        s.map(mapChild)
      case m: Map[_, _] =>
        // `mapValues` is lazy and we need to force it to materialize
        m.mapValues(mapChild).view.force
      case arg: TreeNode[_] if containsChild(arg) => mapTreeNode(arg)
      case Some(child) => Some(mapChild(child))
      case nonChild: AnyRef => nonChild
      case null => null
    }

    if (changed) makeCopy(newArgs) else this
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied to the tree.
   * When `rule` does not apply to a given node it is left unchanged.
   * Users should not expect a specific directionality. If a specific directionality is needed,
   * transformDown or transformUp should be used.
   *
   * @param rule the function use to transform this nodes children
   */
  def transform(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    transformDown(rule)
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied to it and all of its
   * children (pre-order). When `rule` does not apply to a given node it is left unchanged.
   *
   * 返回这个节点的副本，其中 "rule" 已被递归地应用到它和它的所有子节点（预排序）
   * 当`rule`不应用于给定节点时，它将保持不变。
   * @param rule the function used to transform this nodes children
   */
  def transformDown(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    // 如果rule被定义了就使用this,如果没有使用identity(返回这身,等于x=>x)
    val afterRule = CurrentOrigin.withOrigin(origin) {
      rule.applyOrElse(this, identity[BaseType])
    }

    // Check if unchanged and then possibly return old copy to avoid gc churn.
    // 如果相等就使用旧的对象
    if (this fastEquals afterRule) {
      mapChildren(_.transformDown(rule))
    } else {
      afterRule.mapChildren(_.transformDown(rule))
    }
  }

  /**
   * Returns a copy of this node where `rule` has been recursively applied first to all of its
   * children and then itself (post-order). When `rule` does not apply to a given node, it is left
   * unchanged.
   *
   * @param rule the function use to transform this nodes children
   */
  def transformUp(rule: PartialFunction[BaseType, BaseType]): BaseType = {
    val afterRuleOnChildren = mapChildren(_.transformUp(rule))
    if (this fastEquals afterRuleOnChildren) {
      CurrentOrigin.withOrigin(origin) {
        rule.applyOrElse(this, identity[BaseType])
      }
    } else {
      CurrentOrigin.withOrigin(origin) {
        rule.applyOrElse(afterRuleOnChildren, identity[BaseType])
      }
    }
  }

  /**
   * Returns a copy of this node where `f` has been applied to all the nodes children.
   * 返回一个`f`作用于所有子节点后的当前结点的克隆值
   */
  def mapChildren(f: BaseType => BaseType): BaseType = {
    if (children.nonEmpty) {
      var changed = false
      def mapChild(child: Any): Any = child match {
        // child是一个TreeNode并且是当前结点的孩子结点
        case arg: TreeNode[_] if containsChild(arg) =>
          // 执行f后返回
          val newChild = f(arg.asInstanceOf[BaseType])
          if (!(newChild fastEquals arg)) {
            changed = true
            newChild
          } else {
            arg
          }
        // child是Tuple2[TreeNode[_], TreeNode[_]]
        case tuple@(arg1: TreeNode[_], arg2: TreeNode[_]) =>
          // arg1和arg2是当前结点的孩子就执行`f`
          val newChild1 = if (containsChild(arg1)) {
            f(arg1.asInstanceOf[BaseType])
          } else {
            arg1.asInstanceOf[BaseType]
          }

          val newChild2 = if (containsChild(arg2)) {
            f(arg2.asInstanceOf[BaseType])
          } else {
            arg2.asInstanceOf[BaseType]
          }

          if (!(newChild1 fastEquals arg1) || !(newChild2 fastEquals arg2)) {
            changed = true
            (newChild1, newChild2)
          } else {
            tuple
          }
        // 其他的原样返回
        case other => other
      }
      // 处理具体实现类的参数列表，如果其中包含或者嵌套包含Tree[_]就判断是否是当前结点的孩子节点
      // 如果是就执行`f`,否则原样返回
      val newArgs = mapProductIterator {
        // 是当前结点的子节点且是TreeNode[_]就执行`f`
        case arg: TreeNode[_] if containsChild(arg) =>
          val newChild = f(arg.asInstanceOf[BaseType])
          if (!(newChild fastEquals arg)) {
            changed = true
            newChild
          } else {
            arg
          }
        case Some(arg: TreeNode[_]) if containsChild(arg) =>
          val newChild = f(arg.asInstanceOf[BaseType])
          if (!(newChild fastEquals arg)) {
            changed = true
            Some(newChild)
          } else {
            Some(arg)
          }
        case m: Map[_, _] => m.mapValues {
          case arg: TreeNode[_] if containsChild(arg) =>
            val newChild = f(arg.asInstanceOf[BaseType])
            if (!(newChild fastEquals arg)) {
              changed = true
              newChild
            } else {
              arg
            }
          case other => other
        }.view.force // `mapValues` is lazy and we need to force it to materialize
        case d: DataType => d // Avoid unpacking Structs
        case args: Stream[_] => args.map(mapChild).force // Force materialization on stream
        case args: Traversable[_] => args.map(mapChild)
        case nonChild: AnyRef => nonChild
        case null => null
      }
      if (changed) makeCopy(newArgs) else this
    } else {
      this
    }
  }

  /**
   * Args to the constructor that should be copied, but not transformed.
   * These are appended to the transformed args automatically by makeCopy
   * 构造函数的参数应该被复制，但不应该被转换。这些参数会被makeCopy自动附加到转换后的args中。
   * @return
   */
  protected def otherCopyArgs: Seq[AnyRef] = Nil

  /**
   * Creates a copy of this type of tree node after a transformation.
   * Must be overridden by child classes that have constructor arguments
   * that are not present in the productIterator.
   * 转换后创建此类树节点的副本。必须由具有 productIterator 中不存在的构造函数参数的子类覆盖。
   * @param newArgs the new product arguments.
   */
  def makeCopy(newArgs: Array[AnyRef]): BaseType = attachTree(this, "makeCopy") {
    // Skip no-arg constructors that are just there for kryo.
    val ctors = getClass.getConstructors.filter(_.getParameterTypes.size != 0)
    if (ctors.isEmpty) {
      sys.error(s"No valid constructor for $nodeName")
    }
    val allArgs: Array[AnyRef] = if (otherCopyArgs.isEmpty) {
      newArgs
    } else {
      newArgs ++ otherCopyArgs
    }
    val defaultCtor = ctors.find { ctor =>
      if (ctor.getParameterTypes.length != allArgs.length) {
        false
      } else if (allArgs.contains(null)) {
        // if there is a `null`, we can't figure out the class, therefore we should just fallback
        // to older heuristic
        false
      } else {
        val argsArray: Array[Class[_]] = allArgs.map(_.getClass)
        ClassUtils.isAssignable(argsArray, ctor.getParameterTypes, true /* autoboxing */)
      }
    }.getOrElse(ctors.maxBy(_.getParameterTypes.length)) // fall back to older heuristic

    try {
      CurrentOrigin.withOrigin(origin) {
        defaultCtor.newInstance(allArgs.toArray: _*).asInstanceOf[BaseType]
      }
    } catch {
      case e: java.lang.IllegalArgumentException =>
        throw new TreeNodeException(
          this,
          s"""
             |Failed to copy node.
             |Is otherCopyArgs specified correctly for $nodeName.
             |Exception message: ${e.getMessage}
             |ctor: $defaultCtor?
             |types: ${newArgs.map(_.getClass).mkString(", ")}
             |args: ${newArgs.mkString(", ")}
           """.stripMargin)
    }
  }

  /**
   * Returns the name of this type of TreeNode.  Defaults to the class name.
   * Note that we remove the "Exec" suffix for physical operators here.
   */
  def nodeName: String = getClass.getSimpleName.replaceAll("Exec$", "")

  /**
   * The arguments that should be included in the arg string.  Defaults to the `productIterator`.
   * 应该包含在 arg 字符串中的参数
   */
  protected def stringArgs: Iterator[Any] = productIterator

  private lazy val allChildren: Set[TreeNode[_]] = (children ++ innerChildren).toSet[TreeNode[_]]

  /** Returns a string representing the arguments to this node, minus any children */
  def argString: String = stringArgs.flatMap {
    case tn: TreeNode[_] if allChildren.contains(tn) => Nil
    case Some(tn: TreeNode[_]) if allChildren.contains(tn) => Nil
    case Some(tn: TreeNode[_]) => tn.simpleString :: Nil
    case tn: TreeNode[_] => tn.simpleString :: Nil
    case seq: Seq[Any] if seq.toSet.subsetOf(allChildren.asInstanceOf[Set[Any]]) => Nil
    case iter: Iterable[_] if iter.isEmpty => Nil
    case seq: Seq[_] => Utils.truncatedString(seq, "[", ", ", "]") :: Nil
    case set: Set[_] => Utils.truncatedString(set.toSeq, "{", ", ", "}") :: Nil
    case array: Array[_] if array.isEmpty => Nil
    case array: Array[_] => Utils.truncatedString(array, "[", ", ", "]") :: Nil
    case null => Nil
    case None => Nil
    case Some(null) => Nil
    case Some(any) => any :: Nil
    case table: CatalogTable =>
      table.storage.serde match {
        case Some(serde) => table.identifier :: serde :: Nil
        case _ => table.identifier :: Nil
      }
    case other => other :: Nil
  }.mkString(", ")

  /** ONE line description of this node. */
  def simpleString: String = s"$nodeName $argString".trim

  /** ONE line description of this node with more information
   * 一行关于该节点的描述，包括更多的信息
   */
  def verboseString: String

  /** ONE line description of this node with some suffix information */
  def verboseStringWithSuffix: String = verboseString

  override def toString: String = treeString

  /** Returns a string representation of the nodes in this tree */
  def treeString: String = treeString(verbose = true)

  def treeString(verbose: Boolean, addSuffix: Boolean = false): String = {
    generateTreeString(0, Nil, new StringBuilder, verbose = verbose, addSuffix = addSuffix).toString
  }

  /**
   * Returns a string representation of the nodes in this tree, where each operator is numbered.
   * The numbers can be used with [[TreeNode.apply]] to easily access specific subtrees.
   *
   * The numbers are based on depth-first traversal of the tree (with innerChildren traversed first
   * before children).
   * 返回树中节点的字符串表示，其中每个操作者都被编号。
   * 这些数字可以用[[TreeNode.apply]]来轻松访问特定的子树。
   *
   * 这些数字是基于树的深度优先遍历（先遍历innerChildren）顺序生成的
   */
  def numberedTreeString: String =
    treeString.split("\n").zipWithIndex.map { case (line, i) => f"$i%02d $line" }.mkString("\n")

  /**
   * Returns the tree node at the specified number, used primarily for interactive debugging.
   * Numbers for each node can be found in the [[numberedTreeString]].
   *
   * Note that this cannot return BaseType because logical plan's plan node might return
   * physical plan for innerChildren, e.g. in-memory relation logical plan node has a reference
   * to the physical plan node it is referencing.
   */
  def apply(number: Int): TreeNode[_] = getNodeNumbered(new MutableInt(number)).orNull

  /**
   * Returns the tree node at the specified number, used primarily for interactive debugging.
   * Numbers for each node can be found in the [[numberedTreeString]].
   *
   * This is a variant of [[apply]] that returns the node as BaseType (if the type matches).
   */
  def p(number: Int): BaseType = apply(number).asInstanceOf[BaseType]

  /**
   * 根据number找到树中对应的结点，先innerChildren后children
   */
  private def getNodeNumbered(number: MutableInt): Option[TreeNode[_]] = {
    if (number.i < 0) {
      None
    } else if (number.i == 0) {
      Some(this)
    } else {
      number.i -= 1
      // Note that this traversal order must be the same as numberedTreeString.
      innerChildren.map(_.getNodeNumbered(number)).find(_ != None).getOrElse {
        children.map(_.getNodeNumbered(number)).find(_ != None).flatten
      }
    }
  }

  /**
   * All the nodes that should be shown as a inner nested tree of this node.
   * For example, this can be used to show sub-queries.
   * 所有应该显示为该节点的内部嵌套树的节点。例如，这可以用来显示子查询。
   */
  protected def innerChildren: Seq[TreeNode[_]] = Seq.empty

  /**
   * Appends the string representation of this node and its children to the given StringBuilder.
   *
   * The `i`-th element in `lastChildren` indicates whether the ancestor of the current node at
   * depth `i + 1` is the last child of its own parent node.  The depth of the root node is 0, and
   * `lastChildren` for the root node should be empty.
   *
   * Note that this traversal (numbering) order must be the same as [[getNodeNumbered]].
   */
  def generateTreeString(
      depth: Int,
      lastChildren: Seq[Boolean],
      builder: StringBuilder,
      verbose: Boolean,
      prefix: String = "",
      addSuffix: Boolean = false): StringBuilder = {

    if (depth > 0) {
      lastChildren.init.foreach { isLast =>
        builder.append(if (isLast) "   " else ":  ")
      }
      builder.append(if (lastChildren.last) "+- " else ":- ")
    }

    val str = if (verbose) {
      if (addSuffix) verboseStringWithSuffix else verboseString
    } else {
      simpleString
    }
    builder.append(prefix)
    builder.append(str)
    builder.append("\n")

    if (innerChildren.nonEmpty) {
      innerChildren.init.foreach(_.generateTreeString(
        depth + 2, lastChildren :+ children.isEmpty :+ false, builder, verbose,
        addSuffix = addSuffix))
      innerChildren.last.generateTreeString(
        depth + 2, lastChildren :+ children.isEmpty :+ true, builder, verbose,
        addSuffix = addSuffix)
    }

    if (children.nonEmpty) {
      children.init.foreach(_.generateTreeString(
        depth + 1, lastChildren :+ false, builder, verbose, prefix, addSuffix))
      children.last.generateTreeString(
        depth + 1, lastChildren :+ true, builder, verbose, prefix, addSuffix)
    }

    builder
  }

  /**
   * Returns a 'scala code' representation of this `TreeNode` and its children.  Intended for use
   * when debugging where the prettier toString function is obfuscating the actual structure. In the
   * case of 'pure' `TreeNodes` that only contain primitives and other TreeNodes, the result can be
   * pasted in the REPL to build an equivalent Tree.
   * REPL格式的树结构
   */
  def asCode: String = {
    val args = productIterator.map {
      case tn: TreeNode[_] => tn.asCode
      case s: String => "\"" + s + "\""
      case other => other.toString
    }
    s"$nodeName(${args.mkString(",")})"
  }

  def toJSON: String = compact(render(jsonValue))

  def prettyJson: String = pretty(render(jsonValue))

  private def jsonValue: JValue = {
    val jsonValues = scala.collection.mutable.ArrayBuffer.empty[JValue]

    def collectJsonValue(tn: BaseType): Unit = {
      val jsonFields = ("class" -> JString(tn.getClass.getName)) ::
        ("num-children" -> JInt(tn.children.length)) :: tn.jsonFields
      jsonValues += JObject(jsonFields)
      tn.children.foreach(collectJsonValue)
    }

    collectJsonValue(this)
    jsonValues
  }

  protected def jsonFields: List[JField] = {
    // 得到构造器的参数列表
    val fieldNames = getConstructorParameterNames(getClass)
    val fieldValues = productIterator.toSeq ++ otherCopyArgs
    assert(fieldNames.length == fieldValues.length, s"${getClass.getSimpleName} fields: " +
      fieldNames.mkString(", ") + s", values: " + fieldValues.map(_.toString).mkString(", "))

    fieldNames.zip(fieldValues).map {
      // If the field value is a child, then use an int to encode it, represents the index of
      // this child in all children.
      case (name, value: TreeNode[_]) if containsChild(value) =>
        name -> JInt(children.indexOf(value))
      case (name, value: Seq[BaseType]) if value.forall(containsChild) =>
        name -> JArray(
          value.map(v => JInt(children.indexOf(v.asInstanceOf[TreeNode[_]]))).toList
        )
      case (name, value) => name -> parseToJson(value)
    }.toList
  }

  /** Any => JValue */
  private def parseToJson(obj: Any): JValue = obj match {
    case b: Boolean => JBool(b)
    case b: Byte => JInt(b.toInt)
    case s: Short => JInt(s.toInt)
    case i: Int => JInt(i)
    case l: Long => JInt(l)
    case f: Float => JDouble(f)
    case d: Double => JDouble(d)
    case b: BigInt => JInt(b)
    case null => JNull
    case s: String => JString(s)
    case u: UUID => JString(u.toString)
    case dt: DataType => dt.jsonValue
    // SPARK-17356: In usage of mllib, Metadata may store a huge vector of data, transforming
    // it to JSON may trigger OutOfMemoryError.
    case m: Metadata => Metadata.empty.jsonValue
    case clazz: Class[_] => JString(clazz.getName)
    case s: StorageLevel =>
      ("useDisk" -> s.useDisk) ~ ("useMemory" -> s.useMemory) ~ ("useOffHeap" -> s.useOffHeap) ~
        ("deserialized" -> s.deserialized) ~ ("replication" -> s.replication)
    case n: TreeNode[_] => n.jsonValue
    case o: Option[_] => o.map(parseToJson)
    // Recursive scan Seq[TreeNode], Seq[Partitioning], Seq[DataType]
    case t: Seq[_] if t.forall(_.isInstanceOf[TreeNode[_]]) ||
      t.forall(_.isInstanceOf[Partitioning]) || t.forall(_.isInstanceOf[DataType]) =>
      JArray(t.map(parseToJson).toList)
    case t: Seq[_] if t.length > 0 && t.head.isInstanceOf[String] =>
      JString(Utils.truncatedString(t, "[", ", ", "]"))
    case t: Seq[_] => JNull
    case m: Map[_, _] => JNull
    // if it's a scala object, we can simply keep the full class path.
    // TODO: currently if the class name ends with "$", we think it's a scala object, there is
    // probably a better way to check it.
    case obj if obj.getClass.getName.endsWith("$") => "object" -> obj.getClass.getName
    case p: Product if shouldConvertToJson(p) =>
      try {
        val fieldNames = getConstructorParameterNames(p.getClass)
        val fieldValues = p.productIterator.toSeq
        assert(fieldNames.length == fieldValues.length)
        ("product-class" -> JString(p.getClass.getName)) :: fieldNames.zip(fieldValues).map {
          case (name, value) => name -> parseToJson(value)
        }.toList
      } catch {
        case _: RuntimeException => null
      }
    case _ => JNull
  }

  private def shouldConvertToJson(product: Product): Boolean = product match {
    case exprId: ExprId => true
    case field: StructField => true
    case id: IdentifierWithDatabase => true
    case join: JoinType => true
    case spec: BucketSpec => true
    case catalog: CatalogTable => true
    case partition: Partitioning => true
    case resource: FunctionResource => true
    case broadcast: BroadcastMode => true
    case table: CatalogTableType => true
    case storage: CatalogStorageFormat => true
    case _ => false
  }
}
