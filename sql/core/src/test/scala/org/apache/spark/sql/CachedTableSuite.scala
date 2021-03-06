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

package org.apache.spark.sql

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.concurrent.Eventually._

import org.apache.spark.Accumulators
import org.apache.spark.sql.columnar._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.storage.{StorageLevel, RDDBlockId}

private case class BigData(s: String)
//缓存表测试套件
class CachedTableSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  def rddIdOf(tableName: String): Int = {
    val executedPlan = ctx.table(tableName).queryExecution.executedPlan
    executedPlan.collect {
      case InMemoryColumnarTableScan(_, _, relation) =>
        relation.cachedColumnBuffers.id
      case _ =>
        fail(s"Table $tableName is not cached\n" + executedPlan)
    }.head
  }
  //是否被物化
  def isMaterialized(rddId: Int): Boolean = {
    ctx.sparkContext.env.blockManager.get(RDDBlockId(rddId, 0)).nonEmpty
  }

  test("withColumn doesn't invalidate cached dataframe") {//列不被缓存的数据集
    var evalCount = 0
    val myUDF = udf((x: String) => { evalCount += 1; "result" })
    val df = Seq(("test", 1)).toDF("s", "i").select(myUDF($"s"))
    df.cache()

    df.collect()
    assert(evalCount === 1)

    df.collect()
    assert(evalCount === 1)

    val df2 = df.withColumn("newColumn", lit(1))
    df2.collect()

    // We should not reevaluate the cached dataframe
    //我们不应该对缓存的数据集
    assert(evalCount === 1)
  }

  test("cache temp table") {//缓存临时表
    testData.select('key).registerTempTable("tempTable")
    testData.show()
    //testData.show()
    /**
     *+---+
      |_c0|
      +---+
      |100|
      +---+*/
    sql("SELECT COUNT(*) FROM tempTable").show()
    //判断是否被缓存,数据表被缓存,0表示没有缓存表
    assertCached(sql("SELECT COUNT(*) FROM tempTable"), 0)
    //数组缓存
    ctx.cacheTable("tempTable")
    //默认有一条数据表被缓存
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    ctx.uncacheTable("tempTable")
  }

  test("unpersist an uncached table will not raise exception") {
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.persist()
    assert(None != ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = true)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
    testData.unpersist(blocking = false)
    assert(None == ctx.cacheManager.lookupCachedData(testData))
  }
  //缓存表的选择字段
  test("cache table as select") {
    //使用CACHE TABLE命令缓存
    sql("CACHE TABLE tempTable AS SELECT key FROM testData")
    assertCached(sql("SELECT COUNT(*) FROM tempTable"))
    ctx.uncacheTable("tempTable")
  }

  test("uncaching temp table") {//未缓存的临时表
    testData.select('key).registerTempTable("tempTable1")
    testData.select('key).registerTempTable("tempTable2")
    ctx.cacheTable("tempTable1")

    assertCached(sql("SELECT COUNT(*) FROM tempTable1"))
    assertCached(sql("SELECT COUNT(*) FROM tempTable2"))

    // Is this valid?
    //Is this valid? 这是否有效？
    ctx.uncacheTable("tempTable2")

    // Should this be cached?
    //这应该被缓存吗？
    assertCached(sql("SELECT COUNT(*) FROM tempTable1"), 0)
  }

  test("too big for memory") {//太大的内存
    val data = "*" * 1000
    ctx.sparkContext.parallelize(1 to 200000, 1).map(_ => BigData(data)).toDF()
      .registerTempTable("bigData")
      //持久化内存和磁盘
    ctx.table("bigData").persist(StorageLevel.MEMORY_AND_DISK)
    assert(ctx.table("bigData").count() === 200000L)
    ctx.table("bigData").unpersist(blocking = true)
  }

  test("calling .cache() should use in-memory columnar caching") {//调用cache在内存中的缓存列
    ctx.table("testData").cache()
    assertCached(ctx.table("testData"))
    ctx.table("testData").unpersist(blocking = true)
  }
  //调用unpersist在内存中删除列的缓存
  test("calling .unpersist() should drop in-memory columnar cache") {
    ctx.table("testData").cache()
    ctx.table("testData").count()
    ctx.table("testData").unpersist(blocking = true)
    //判断是否有缓存的表
    assertCached(ctx.table("testData"), 0)
  }

  test("isCached") {//是否缓存
    ctx.cacheTable("testData")

    assertCached(ctx.table("testData"))
    assert(ctx.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => true
      case _ => false
    })

    ctx.uncacheTable("testData")
    assert(!ctx.isCached("testData"))//是否被缓存
    assert(ctx.table("testData").queryExecution.withCachedData match {
      case _: InMemoryRelation => false
      case _ => true
    })
  }

  test("SPARK-1669: cacheTable should be idempotent") {//缓存表应该是幂等
    assume(!ctx.table("testData").logicalPlan.isInstanceOf[InMemoryRelation])

    ctx.cacheTable("testData")
    assertCached(ctx.table("testData"))

    assertResult(1, "InMemoryRelation not found, testData should have been cached") {
      ctx.table("testData").queryExecution.withCachedData.collect {
        case r: InMemoryRelation => r
      }.size
    }

    ctx.cacheTable("testData")
    assertResult(0, "Double InMemoryRelations found, cacheTable() is not idempotent") {
      ctx.table("testData").queryExecution.withCachedData.collect {
        case r @ InMemoryRelation(_, _, _, _, _: InMemoryColumnarTableScan, _) => r
      }.size
    }

    ctx.uncacheTable("testData")
  }
  //从缓存表和未缓存中读取
  test("read from cached table and uncache") {
    ctx.cacheTable("testData")
    checkAnswer(ctx.table("testData"), testData.collect().toSeq)
    assertCached(ctx.table("testData"))

    ctx.uncacheTable("testData")
    checkAnswer(ctx.table("testData"), testData.collect().toSeq)
    assertCached(ctx.table("testData"), 0)
  }
  //非缓存表的联合缓存的正确错误
  test("correct error on uncache of non-cached table") {
    intercept[IllegalArgumentException] {
      ctx.uncacheTable("testData")
    }
  }
  //缓存表
  test("SELECT star from cached table") {
     
    sql("SELECT * FROM testData").registerTempTable("selectStar")
    ctx.cacheTable("selectStar")
    checkAnswer(
      sql("SELECT * FROM selectStar WHERE key = 1"),
      Seq(Row(1, "1")))
    ctx.uncacheTable("selectStar")
  }

  test("Self-join cached") {//自连接加入缓存
    val unCachedAnswer =
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key").collect()
    ctx.cacheTable("testData")
    checkAnswer(
      sql("SELECT * FROM testData a JOIN testData b ON a.key = b.key"),
      unCachedAnswer.toSeq)
    ctx.uncacheTable("testData")
  }
  //“缓存表'和未缓存表的SQL语句
  test("'CACHE TABLE' and 'UNCACHE TABLE' SQL statement") {
    sql("CACHE TABLE testData")
    assertCached(ctx.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    sql("UNCACHE TABLE testData")
    assert(!ctx.isCached("testData"), "Table 'testData' should not be cached")

    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }
//缓存表名
  test("CACHE TABLE tableName AS SELECT * FROM anotherTable") {
    sql("CACHE TABLE testCacheTable AS SELECT * FROM testData")
    assertCached(ctx.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    ctx.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }
  //缓存表的选择
  test("CACHE TABLE tableName AS SELECT ...") {
    sql("CACHE TABLE testCacheTable AS SELECT key FROM testData LIMIT 10")
    assertCached(ctx.table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    ctx.uncacheTable("testCacheTable")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }
  //延迟缓存表
  test("CACHE LAZY TABLE tableName") {
    sql("CACHE LAZY TABLE testData")
    assertCached(ctx.table("testData"))

    val rddId = rddIdOf("testData")
    assert(
      !isMaterialized(rddId),
      "Lazily cached in-memory table shouldn't be materialized eagerly")

    sql("SELECT COUNT(*) FROM testData").collect()
    assert(
      isMaterialized(rddId),
      "Lazily cached in-memory table should have been materialized")

    ctx.uncacheTable("testData")
    eventually(timeout(10 seconds)) {
      assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
    }
  }

  test("InMemoryRelation statistics") {//在内存关系统计中
    sql("CACHE TABLE testData")
    ctx.table("testData").queryExecution.withCachedData.collect {
      case cached: InMemoryRelation =>
        val actualSizeInBytes = (1 to 100).map(i => INT.defaultSize + i.toString.length + 4).sum
        assert(cached.statistics.sizeInBytes === actualSizeInBytes)
    }
  }

  test("Drops temporary table") {//删除临时表
    testData.select('key).registerTempTable("t1")
    ctx.table("t1")
    ctx.dropTempTable("t1")
    assert(intercept[RuntimeException](ctx.table("t1")).getMessage.startsWith("Table Not Found"))
  }

  test("Drops cached temporary table") {//删除缓存临时表
    testData.select('key).registerTempTable("t1")
    testData.select('key).registerTempTable("t2")
    ctx.cacheTable("t1")

    assert(ctx.isCached("t1"))
    assert(ctx.isCached("t2"))

    ctx.dropTempTable("t1")
    assert(intercept[RuntimeException](ctx.table("t1")).getMessage.startsWith("Table Not Found"))
    assert(!ctx.isCached("t2"))
  }

  test("Clear all cache") {//清理缓存
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    ctx.cacheTable("t1")
    ctx.cacheTable("t2")
    ctx.clearCache()
    assert(ctx.cacheManager.isEmpty)

    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")
    ctx.cacheTable("t1")
    ctx.cacheTable("t2")
    sql("Clear CACHE")
    assert(ctx.cacheManager.isEmpty)
  }
  //清除累加器uncacheTable防止内存泄漏
  test("Clear accumulators when uncacheTable to prevent memory leaking") {
    sql("SELECT key FROM testData LIMIT 10").registerTempTable("t1")
    sql("SELECT key FROM testData LIMIT 5").registerTempTable("t2")

    ctx.cacheTable("t1")
    ctx.cacheTable("t2")

    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()
    sql("SELECT * FROM t1").count()
    sql("SELECT * FROM t2").count()

    Accumulators.synchronized {
      val accsSize = Accumulators.originals.size
      ctx.uncacheTable("t1")
      ctx.uncacheTable("t2")
      assert((accsSize - 2) == Accumulators.originals.size)
    }
  }
}
