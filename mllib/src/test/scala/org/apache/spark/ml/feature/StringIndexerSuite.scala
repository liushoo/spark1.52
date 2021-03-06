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

package org.apache.spark.ml.feature

import org.apache.spark.sql.types.{StringType, StructType, StructField, DoubleType}
import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.ml.attribute.{Attribute, NominalAttribute}
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.ml.util.MLTestingUtils
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
/**
 * StringIndexer可以把一个属性列里的值映射成数值类型
 */
class StringIndexerSuite extends SparkFunSuite with MLlibTestSparkContext {

  test("params") {//参数
    ParamsSuite.checkParams(new StringIndexer)
    val model = new StringIndexerModel("indexer", Array("a", "b"))
    val modelWithoutUid = new StringIndexerModel(Array("a", "b"))
    ParamsSuite.checkParams(model)
    ParamsSuite.checkParams(modelWithoutUid)
  }

  test("StringIndexer") {//StringIndexer可以把一个属性列里的值映射成数值类型
    val data = sc.parallelize(Seq((0, "a"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "c")), 2)
    val df = sqlContext.createDataFrame(data).toDF("id", "label")
    //1)按照 Label 出现的频次对其进行序列编码,如0,1,2,… Array[String] = Array(a, c, b),a出次3次,c出现2次,b出现1次
    //2)fit()方法将DataFrame转化为一个Transformer的算法
    val indexer = new StringIndexer().setInputCol("label").setOutputCol("labelIndex").fit(df)

    // copied model must have the same parent.
    //复制的模型必须有相同的父
    MLTestingUtils.checkCopy(indexer)
    //主要是用来把 一个 StringIndexer 转换成另一个 DataFrame
    val transformed = indexer.transform(df)
    /**
      +---+-----+----------+
      | id|label|labelIndex|
      +---+-----+----------+
      |  0|    a|       0.0|
      |  1|    b|       2.0|
      |  2|    c|       1.0|
      |  3|    a|       0.0|
      |  4|    a|       0.0|
      |  5|    c|       1.0|
      +---+-----+----------+*/
    transformed.show()
    val attr = Attribute.fromStructField(transformed.schema("labelIndex"))
      .asInstanceOf[NominalAttribute]
    assert(attr.values.get === Array("a", "c", "b"))
    //
    val output = transformed.select("id", "labelIndex","label").map { r =>
      //println(r(0)+">>>>"+r(1)+">>>>"+r)
      (r.getInt(0), r.getDouble(1))//a,c,b转换double类型
    }.collect().toSet
    // a -> 0, b -> 2, c -> 1
    val expected = Set((0, 0.0), (1, 2.0), (2, 1.0), (3, 0.0), (4, 0.0), (5, 1.0))
    assert(output === expected)
  }

  test("StringIndexer with a numeric input column") {//一个数字输入列字符串索引
    val data = sc.parallelize(Seq((0, 100), (1, 200), (2, 300), (3, 100), (4, 100), (5, 300)), 2)
    val df = sqlContext.createDataFrame(data).toDF("id", "label")
    //fit()方法将DataFrame转化为一个Transformer的算法
    val indexer = new StringIndexer().setInputCol("label").setOutputCol("labelIndex").fit(df)
    val transformed = indexer.transform(df)
    val attr = Attribute.fromStructField(transformed.schema("labelIndex")).asInstanceOf[NominalAttribute]
    assert(attr.values.get === Array("100", "300", "200"))
    val output = transformed.select("id", "labelIndex","label").map { r =>
      println(r)
      (r.getInt(0), r.getDouble(1))
    }.collect().toSet
    // 100 -> 0, 200 -> 2, 300 -> 1
    val expected = Set((0, 0.0), (1, 2.0), (2, 1.0), (3, 0.0), (4, 0.0), (5, 1.0))
    assert(output === expected)
  }
  //字符串索引模型应该保持沉默,如果输入列不存在
  test("StringIndexerModel should keep silent if the input column does not exist.") {
    val indexerModel = new StringIndexerModel("indexer", Array("a", "b", "c"))
      .setInputCol("label")
      .setOutputCol("labelIndex")
    val df = sqlContext.range(0L, 10L)
     //transform()方法将DataFrame转化为另外一个DataFrame的算法
    assert(indexerModel.transform(df).eq(df))
  }

  test("IndexToString params") {//IndexToString参数
    val idxToStr = new IndexToString()
    ParamsSuite.checkParams(idxToStr)
  }

  test("IndexToString.transform") {//IndexToString转换
    val labels = Array("a", "b", "c")
    val df0 = sqlContext.createDataFrame(Seq(
      (0, "a"), (1, "b"), (2, "c"), (0, "a")
    )).toDF("index", "expected")
    /**
    +-----+--------+
    |index|expected|
    +-----+--------+
    |    0|       a|
    |    1|       b|
    |    2|       c|
    |    0|       a|
    +-----+--------+*/
    df0.show()
    val idxToStr0 = new IndexToString()
      .setInputCol("index")//输入列
      .setOutputCol("actual")//输出列
      .setLabels(labels)//
       //transform()方法将DataFrame转化为另外一个DataFrame的算法
      /**
      +-----+--------+------+
      |index|expected|actual|
      +-----+--------+------+
      |    0|       a|     a|
      |    1|       b|     b|
      |    2|       c|     c|
      |    0|       a|     a|
      +-----+--------+------+*/
      idxToStr0.transform(df0).show()
    idxToStr0.transform(df0).select("actual", "expected").collect().foreach {
      case Row(actual, expected) =>
        assert(actual === expected)
    }

    val attr = NominalAttribute.defaultAttr.withValues(labels)
    val df1 = df0.select(col("index").as("indexWithAttr", attr.toMetadata()), col("expected"))

    val idxToStr1 = new IndexToString().setInputCol("indexWithAttr").setOutputCol("actual")
    idxToStr1.transform(df1).select("actual", "expected").collect().foreach {
      case Row(actual, expected) =>
        assert(actual === expected)
    }
  }

  test("StringIndexer, IndexToString are inverses") {//转换
    val data = sc.parallelize(Seq((0, "a"), (1, "b"), (2, "c"), (3, "a"), (4, "a"), (5, "c")), 2)
    val df = sqlContext.createDataFrame(data).toDF("id", "label")
    /**
      +---+-----+
      | id|label|
      +---+-----+
      |  0|    a|
      |  1|    b|
      |  2|    c|
      |  3|    a|
      |  4|    a|
      |  5|    c|
      +---+-----+*/
    df.show()
    //indexer.labels = Array(a, c, b)
    //fit()方法将DataFrame转化为一个Transformer的算法
    val indexer = new StringIndexer().setInputCol("label").setOutputCol("labelIndex").fit(df)
    val transformed = indexer.transform(df)
    /**
     *+---+-----+----------+
      | id|label|labelIndex|
      +---+-----+----------+
      |  0|    a|       0.0|
      |  1|    b|       2.0|
      |  2|    c|       1.0|
      |  3|    a|       0.0|
      |  4|    a|       0.0|
      |  5|    c|       1.0|
      +---+-----+----------+*/
    transformed.show()
    //labelIndex = Array([0.0,a], [2.0,b], [1.0,c], [0.0,a], [0.0,a], [1.0,c])
    val labelIndex=transformed.select("labelIndex", "label").collect()
    //setLabels 设置标签列表indexer.labels = Array(a, c, b)
    val idx2str = new IndexToString().setInputCol("labelIndex").setOutputCol("sameLabel").setLabels(indexer.labels)
     //transform()方法将DataFrame转化为另外一个DataFrame的算法
    idx2str.transform(transformed).select("label", "sameLabel").collect().foreach {
      case Row(a: String, b: String) =>
        assert(a === b)
    }
  }

  test("IndexToString.transformSchema (SPARK-10573)") {//索引转换字符串
    val idxToStr = new IndexToString().setInputCol("input").setOutputCol("output")
    val inSchema = StructType(Seq(StructField("input", DoubleType)))
    val outSchema = idxToStr.transformSchema(inSchema)
    assert(outSchema("output").dataType === StringType)
  }
}
