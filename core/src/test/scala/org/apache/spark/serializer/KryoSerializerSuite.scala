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

package org.apache.spark.serializer

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.collection.mutable
import scala.reflect.ClassTag

import com.esotericsoftware.kryo.Kryo

import org.apache.spark.{SharedSparkContext, SparkConf, SparkFunSuite}
import org.apache.spark.scheduler.HighlyCompressedMapStatus
import org.apache.spark.serializer.KryoTest._
import org.apache.spark.storage.BlockManagerId

class KryoSerializerSuite extends SparkFunSuite with SharedSparkContext {
  conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  conf.set("spark.kryo.registrator", classOf[MyRegistrator].getName)

  test("SPARK-7392 configuration limits") {//配置限制
    val kryoBufferProperty = "spark.kryoserializer.buffer"
    val kryoBufferMaxProperty = "spark.kryoserializer.buffer.max"

    def newKryoInstance(
        conf: SparkConf,
        bufferSize: String = "64k",
        maxBufferSize: String = "64m"): SerializerInstance = {
      val kryoConf = conf.clone()
      kryoConf.set(kryoBufferProperty, bufferSize)
      kryoConf.set(kryoBufferMaxProperty, maxBufferSize)
      new KryoSerializer(kryoConf).newInstance()
    }

    // test default values
    //测试的默认值
    newKryoInstance(conf, "64k", "64m")
    // 2048m = 2097152k
    // should not throw exception when kryoBufferMaxProperty < kryoBufferProperty
    //应该抛出异常
    newKryoInstance(conf, "2097151k", "64m")
    // test maximum size with unit of KiB
    // 测试最大限度大小直到KiB
    newKryoInstance(conf, "2097151k", "2097151k")
    // should throw exception with bufferSize out of bound
    //应该抛出异常与缓存的绑定
    val thrown1 = intercept[IllegalArgumentException](newKryoInstance(conf, "2048m"))
    assert(thrown1.getMessage.contains(kryoBufferProperty))
    // should throw exception with maxBufferSize out of bound
    //应该抛出异常与maxbuffersize冲出绑定
    val thrown2 = intercept[IllegalArgumentException](
        newKryoInstance(conf, maxBufferSize = "2048m"))
    assert(thrown2.getMessage.contains(kryoBufferMaxProperty))
    // should throw exception when both bufferSize and maxBufferSize out of bound
    // exception should only contain "spark.kryoserializer.buffer"
    val thrown3 = intercept[IllegalArgumentException](newKryoInstance(conf, "2g", "3g"))
    assert(thrown3.getMessage.contains(kryoBufferProperty))
    assert(!thrown3.getMessage.contains(kryoBufferMaxProperty))
    // test configuration with mb is supported properly
    //测试配置支持MB属性
    newKryoInstance(conf, "8m", "9m")
  }

  test("basic types") {//基本类型
    val ser = new KryoSerializer(conf).newInstance()
    def check[T: ClassTag](t: T) {
      assert(ser.deserialize[T](ser.serialize(t)) === t)
    }
    check(1)
    check(1L)
    check(1.0f)
    check(1.0)
    check(1.toByte)
    check(1.toShort)
    check("")
    check("hello")
    check(Integer.MAX_VALUE)
    check(Integer.MIN_VALUE)
    check(java.lang.Long.MAX_VALUE)
    check(java.lang.Long.MIN_VALUE)
    check[String](null)
    check(Array(1, 2, 3))
    check(Array(1L, 2L, 3L))
    check(Array(1.0, 2.0, 3.0))
    check(Array(1.0f, 2.9f, 3.9f))
    check(Array("aaa", "bbb", "ccc"))
    check(Array("aaa", "bbb", null))
    check(Array(true, false, true))
    check(Array('a', 'b', 'c'))
    check(Array[Int]())
    check(Array(Array("1", "2"), Array("1", "2", "3", "4")))
  }

  test("pairs") {//键值对
    val ser = new KryoSerializer(conf).newInstance()
    def check[T: ClassTag](t: T) {
      assert(ser.deserialize[T](ser.serialize(t)) === t)
    }
    check((1, 1))
    check((1, 1L))
    check((1L, 1))
    check((1L, 1L))
    check((1.0, 1))
    check((1, 1.0))
    check((1.0, 1.0))
    check((1.0, 1L))
    check((1L, 1.0))
    check((1.0, 1L))
    check(("x", 1))
    check(("x", 1.0))
    check(("x", 1L))
    check((1, "x"))
    check((1.0, "x"))
    check((1L, "x"))
    check(("x", "x"))
  }

  test("Scala data structures") {//数据结构
    val ser = new KryoSerializer(conf).newInstance()
    def check[T: ClassTag](t: T) {
      assert(ser.deserialize[T](ser.serialize(t)) === t)
    }
    check(List[Int]())
    check(List[Int](1, 2, 3))
    check(List[String]())
    check(List[String]("x", "y", "z"))
    check(None)
    check(Some(1))
    check(Some("hi"))
    check(mutable.ArrayBuffer(1, 2, 3))
    check(mutable.ArrayBuffer("1", "2", "3"))
    check(mutable.Map())
    check(mutable.Map(1 -> "one", 2 -> "two"))
    check(mutable.Map("one" -> 1, "two" -> 2))
    check(mutable.HashMap(1 -> "one", 2 -> "two"))
    check(mutable.HashMap("one" -> 1, "two" -> 2))
    check(List(Some(mutable.HashMap(1->1, 2->2)), None, Some(mutable.HashMap(3->4))))
    check(List(
      mutable.HashMap("one" -> 1, "two" -> 2),
      mutable.HashMap(1->"one", 2->"two", 3->"three")))
  }

  test("ranges") {//范围
    val ser = new KryoSerializer(conf).newInstance()
    def check[T: ClassTag](t: T) {
      assert(ser.deserialize[T](ser.serialize(t)) === t)
      // Check that very long ranges don't get written one element at a time
      //检查一个很长的范围不会一次写入一个元素
      assert(ser.serialize(t).limit < 100)
    }
    check(1 to 1000000)
    check(1 to 1000000 by 2)
    check(1 until 1000000)
    check(1 until 1000000 by 2)
    check(1L to 1000000L)
    check(1L to 1000000L by 2L)
    check(1L until 1000000L)
    check(1L until 1000000L by 2L)
    check(1.0 to 1000000.0 by 1.0)
    check(1.0 to 1000000.0 by 2.0)
    check(1.0 until 1000000.0 by 1.0)
    check(1.0 until 1000000.0 by 2.0)
  }

  test("asJavaIterable") {//作为java迭代器
    // Serialize a collection wrapped by asJavaIterable
    //序列一个集合包装asJavaIterable
    val ser = new KryoSerializer(conf).newInstance()
    val a = ser.serialize(scala.collection.convert.WrapAsJava.asJavaIterable(Seq(12345)))
    val b = ser.deserialize[java.lang.Iterable[Int]](a)
    assert(b.iterator().next() === 12345)

    // Serialize a normal Java collection
    //序列化一个正常的java集合
    val col = new java.util.ArrayList[Int]
    col.add(54321)
    val c = ser.serialize(col)
    val d = ser.deserialize[java.lang.Iterable[Int]](c)
    assert(b.iterator().next() === 12345)
  }

  test("custom registrator") {//自定义注册器
    val ser = new KryoSerializer(conf).newInstance()
    def check[T: ClassTag](t: T) {
      assert(ser.deserialize[T](ser.serialize(t)) === t)
    }

    check(CaseClass(17, "hello"))

    val c1 = new ClassWithNoArgConstructor
    c1.x = 32
    check(c1)

    val c2 = new ClassWithoutNoArgConstructor(47)
    check(c2)

    val hashMap = new java.util.HashMap[String, String]
    hashMap.put("foo", "bar")
    check(hashMap)

    System.clearProperty("spark.kryo.registrator")
  }

  test("kryo with collect") {//kryo的集合
    val control = 1 :: 2 :: Nil
    val result = sc.parallelize(control, 2)
      .map(new ClassWithoutNoArgConstructor(_))
      .collect()
      .map(_.x)
    assert(control === result.toSeq)
  }

  test("kryo with parallelize") {//kryo的并行化
    val control = 1 :: 2 :: Nil
    val result = sc.parallelize(control.map(new ClassWithoutNoArgConstructor(_))).map(_.x).collect()
    assert (control === result.toSeq)
  }

  test("kryo with parallelize for specialized tuples") {//kryo的并行化指定元组
    assert (sc.parallelize( Array((1, 11), (2, 22), (3, 33)) ).count === 3)
  }

  test("kryo with parallelize for primitive arrays") {//kryo的并行化指定数组
    assert (sc.parallelize( Array(1, 2, 3) ).count === 3)
  }

  test("kryo with collect for specialized tuples") {//kryo的集合指定元组
    assert (sc.parallelize( Array((1, 11), (2, 22), (3, 33)) ).collect().head === (1, 11))
  }

  test("kryo with SerializableHyperLogLog") {
    assert(sc.parallelize( Array(1, 2, 3, 2, 3, 3, 2, 3, 1) ).countApproxDistinct(0.01) === 3)
  }

  test("kryo with reduce") {
    val control = 1 :: 2 :: Nil
    val result = sc.parallelize(control, 2).map(new ClassWithoutNoArgConstructor(_))
        .reduce((t1, t2) => new ClassWithoutNoArgConstructor(t1.x + t2.x)).x
    assert(control.sum === result)
  }

  test("kryo with fold") {//kryo的折叠
    val control = 1 :: 2 :: Nil
    // zeroValue must not be a ClassWithoutNoArgConstructor instance because it will be
    // serialized by spark.closure.serializer but spark.closure.serializer only supports
    // the default Java serializer.
    val result = sc.parallelize(control, 2).map(new ClassWithoutNoArgConstructor(_))
      .fold(null)((t1, t2) => {
      val t1x = if (t1 == null) 0 else t1.x
      new ClassWithoutNoArgConstructor(t1x + t2.x)
    }).x
    assert(control.sum === result)
  }
  //kryo不存在自定义注册器失败
  test("kryo with nonexistent custom registrator should fail") {
    import org.apache.spark.SparkException

    val conf = new SparkConf(false)
    conf.set("spark.kryo.registrator", "this.class.does.not.exist")

    val thrown = intercept[SparkException](new KryoSerializer(conf).newInstance())
    assert(thrown.getMessage.contains("Failed to register classes with Kryo"))
  }
  //默认类加载程序可以由一个不同的线程设置
  test("default class loader can be set by a different thread") {
    val ser = new KryoSerializer(new SparkConf)

    // First serialize the object
    //首先将对象
    val serInstance = ser.newInstance()
    val bytes = serInstance.serialize(new ClassLoaderTestingObject)

    // Deserialize the object to make sure normal deserialization works
    //反序列化对象的反序列化以确保正常工作
    serInstance.deserialize[ClassLoaderTestingObject](bytes)

    // Set a special, broken ClassLoader and make sure we get an exception on deserialization
    //设置一个特殊类装载器,确保我们能在反序列化一个例外
    ser.setDefaultClassLoader(new ClassLoader() {
      override def loadClass(name: String): Class[_] = throw new UnsupportedOperationException
    })
    intercept[UnsupportedOperationException] {
      ser.newInstance().deserialize[ClassLoaderTestingObject](bytes)
    }
  }

  test("registration of HighlyCompressedMapStatus") {
    val conf = new SparkConf(false)
    conf.set("spark.kryo.registrationRequired", "true")

    // these cases require knowing the internals of RoaringBitmap a little.  Blocks span 2^16
    // values, and they use a bitmap (dense) if they have more than 4096 values, and an
    // array (sparse) if they use less.  So we just create two cases, one sparse and one dense.
    // and we use a roaring bitmap for the empty blocks, so we trigger the dense case w/ mostly
    // empty blocks

    val ser = new KryoSerializer(conf).newInstance()
    val denseBlockSizes = new Array[Long](5000)
    val sparseBlockSizes = Array[Long](0L, 1L, 0L, 2L)
    Seq(denseBlockSizes, sparseBlockSizes).foreach { blockSizes =>
      ser.serialize(HighlyCompressedMapStatus(BlockManagerId("exec-1", "host", 1234), blockSizes))
    }
  }
  //序列化缓冲区溢出报告
  test("serialization buffer overflow reporting") {
    import org.apache.spark.SparkException
    val kryoBufferMaxProperty = "spark.kryoserializer.buffer.max"

    val largeObject = (1 to 1000000).toArray

    val conf = new SparkConf(false)
    conf.set(kryoBufferMaxProperty, "1")

    val ser = new KryoSerializer(conf).newInstance()
    val thrown = intercept[SparkException](ser.serialize(largeObject))
    assert(thrown.getMessage.contains(kryoBufferMaxProperty))
  }

  test("getAutoReset") {
    val ser = new KryoSerializer(new SparkConf).newInstance().asInstanceOf[KryoSerializerInstance]
    assert(ser.getAutoReset)
    val conf = new SparkConf().set("spark.kryo.registrator",
      classOf[RegistratorWithoutAutoReset].getName)
    val ser2 = new KryoSerializer(conf).newInstance().asInstanceOf[KryoSerializerInstance]
    assert(!ser2.getAutoReset)
  }

  private def testSerializerInstanceReuse(autoReset: Boolean, referenceTracking: Boolean): Unit = {
    val conf = new SparkConf(loadDefaults = false)
      .set("spark.kryo.referenceTracking", referenceTracking.toString)
    if (!autoReset) {
      conf.set("spark.kryo.registrator", classOf[RegistratorWithoutAutoReset].getName)
    }
    val ser = new KryoSerializer(conf)
    val serInstance = ser.newInstance().asInstanceOf[KryoSerializerInstance]
    assert (serInstance.getAutoReset() === autoReset)
    val obj = ("Hello", "World")
    def serializeObjects(): Array[Byte] = {
      val baos = new ByteArrayOutputStream()
      val serStream = serInstance.serializeStream(baos)
      serStream.writeObject(obj)
      serStream.writeObject(obj)
      serStream.close()
      baos.toByteArray
    }
    val output1: Array[Byte] = serializeObjects()
    val output2: Array[Byte] = serializeObjects()
    assert (output1 === output2)
  }

  // Regression test for SPARK-7766, an issue where disabling auto-reset and enabling
  // reference-tracking would lead to corrupted output when serializer instances are re-used
  // SPARK-7766的回归测试，这是禁用自动复位和启用的问题,引用跟踪会导致在重复使用序列化器实例时出现损坏的输出
  for (referenceTracking <- Set(true, false); autoReset <- Set(true, false)) {
    test(s"instance reuse with autoReset = $autoReset, referenceTracking = $referenceTracking") {
      testSerializerInstanceReuse(autoReset = autoReset, referenceTracking = referenceTracking)
    }
  }
}

class KryoSerializerAutoResetDisabledSuite extends SparkFunSuite with SharedSparkContext {
  conf.set("spark.serializer", classOf[KryoSerializer].getName)
  conf.set("spark.kryo.registrator", classOf[RegistratorWithoutAutoReset].getName)
  conf.set("spark.kryo.referenceTracking", "true")
  conf.set("spark.shuffle.manager", "sort")
  //用于设置在Reducer的partition数目少于多少的时候,Sort Based Shuffle内部不使用Merge Sort的方式处理数据,而是直接将每个partition写入单独的文件
  conf.set("spark.shuffle.sort.bypassMergeThreshold", "200")

  test("sort-shuffle with bypassMergeSort (SPARK-7873)") {
    val myObject = ("Hello", "World")
    assert(sc.parallelize(Seq.fill(100)(myObject)).repartition(2).collect().toSet === Set(myObject))
  }

  test("calling deserialize() after deserializeStream()") {
    val serInstance = new KryoSerializer(conf).newInstance().asInstanceOf[KryoSerializerInstance]
    assert(!serInstance.getAutoReset())
    val hello = "Hello"
    val world = "World"
    // Here, we serialize the same value twice, so the reference-tracking should cause us to store
    // references to some of these values
    //这里我们串行化相同的值两次,所以引用跟踪应该使我们存储引用这些值中的一些
    val helloHello = serInstance.serialize((hello, hello))
    // Here's a stream which only contains one value
    //这是一个只包含一个值的流
    val worldWorld: Array[Byte] = {
      val baos = new ByteArrayOutputStream()
      val serStream = serInstance.serializeStream(baos)
      serStream.writeObject(world)
      serStream.writeObject(world)
      serStream.close()
      baos.toByteArray
    }
    val deserializationStream = serInstance.deserializeStream(new ByteArrayInputStream(worldWorld))
    assert(deserializationStream.readValue[Any]() === world)
    deserializationStream.close()
    assert(serInstance.deserialize[Any](helloHello) === (hello, hello))
  }
}

class ClassLoaderTestingObject


object KryoTest {

  case class CaseClass(i: Int, s: String) {}

  class ClassWithNoArgConstructor {
    var x: Int = 0
    override def equals(other: Any): Boolean = other match {
      case c: ClassWithNoArgConstructor => x == c.x
      case _ => false
    }
  }

  class ClassWithoutNoArgConstructor(val x: Int) {
    override def equals(other: Any): Boolean = other match {
      case c: ClassWithoutNoArgConstructor => x == c.x
      case _ => false
    }
  }

  class MyRegistrator extends KryoRegistrator {
    override def registerClasses(k: Kryo) {
      k.register(classOf[CaseClass])
      k.register(classOf[ClassWithNoArgConstructor])
      k.register(classOf[ClassWithoutNoArgConstructor])
      k.register(classOf[java.util.HashMap[_, _]])
    }
  }

  class RegistratorWithoutAutoReset extends KryoRegistrator {
    override def registerClasses(k: Kryo) {
      k.setAutoReset(false)
    }
  }
}
