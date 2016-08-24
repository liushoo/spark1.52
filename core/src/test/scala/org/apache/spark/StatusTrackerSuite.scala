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

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.language.postfixOps

import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually._

import org.apache.spark.JobExecutionStatus._

class StatusTrackerSuite extends SparkFunSuite with Matchers with LocalSparkContext {

  test("basic status API usage") {
    sc = new SparkContext("local", "test", new SparkConf(false))
    //collectAsync 异步集合
    val jobFuture = sc.parallelize(1 to 10000, 2).map(identity).groupBy(identity).collectAsync()
    //eventually
    // 柯里化(Currying)是把接受多个参数的函数变换成接受一个单一参数(最初函数的第一个参数)的函数，
    // 并且返回接受余下的参数且返回结果
    val jobId: Int = eventually(timeout(10 seconds)) {//简写匿名函数写法
      val jobIds = jobFuture.jobIds//
      jobIds.size should be(1)
      jobIds.head//取出列表第一值
    }
    
    val jobInfo = eventually(timeout(10 seconds)) {//jobInfo:SparkJobInfo
      sc.statusTracker.getJobInfo(jobId).get
    }
    jobInfo.status() should not be FAILED
    val stageIds = jobInfo.stageIds()//获得SageId
    stageIds.size should be(2)

    val firstStageInfo = eventually(timeout(10 seconds)) {
      sc.statusTracker.getStageInfo(stageIds(0)).get
    }
    firstStageInfo.stageId() should be(stageIds(0))
    firstStageInfo.currentAttemptId() should be(0)
    firstStageInfo.numTasks() should be(2)//获得任务数
    eventually(timeout(10 seconds)) {
      val updatedFirstStageInfo = sc.statusTracker.getStageInfo(stageIds(0)).get
      updatedFirstStageInfo.numCompletedTasks() should be(2) //完成任务数
      updatedFirstStageInfo.numActiveTasks() should be(0)//任务活动数
      updatedFirstStageInfo.numFailedTasks() should be(0) //任务失败数
    }
  }

  test("getJobIdsForGroup()") {
    sc = new SparkContext("local", "test", new SparkConf(false))
    // Passing `null` should return jobs that were not run in a job group:
    val defaultJobGroupFuture = sc.parallelize(1 to 1000).countAsync()
    val defaultJobGroupJobId = eventually(timeout(10 seconds)) {
      defaultJobGroupFuture.jobIds.head
    }
    eventually(timeout(10 seconds)) {
      sc.statusTracker.getJobIdsForGroup(null).toSet should be (Set(defaultJobGroupJobId))
    }
    // Test jobs submitted in job groups:
    sc.setJobGroup("my-job-group", "description")//设置Job分组
    sc.statusTracker.getJobIdsForGroup("my-job-group") should be (Seq.empty)
    val firstJobFuture = sc.parallelize(1 to 1000).countAsync()
    val firstJobId = eventually(timeout(10 seconds)) {
      firstJobFuture.jobIds.head
    }
    eventually(timeout(10 seconds)) {
      sc.statusTracker.getJobIdsForGroup("my-job-group") should be (Seq(firstJobId))
    }
    val secondJobFuture = sc.parallelize(1 to 1000).countAsync()
    val secondJobId = eventually(timeout(10 seconds)) {
      secondJobFuture.jobIds.head
    }
    eventually(timeout(10 seconds)) {
      sc.statusTracker.getJobIdsForGroup("my-job-group").toSet should be (
        Set(firstJobId, secondJobId))
    }
  }
}
