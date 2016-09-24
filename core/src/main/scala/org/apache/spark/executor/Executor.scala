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

package org.apache.spark.executor

import java.io.File
import java.io.NotSerializableException
import java.lang.management.ManagementFactory
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.scheduler.DirectTaskResult
import org.apache.spark.scheduler.IndirectTaskResult
import org.apache.spark.scheduler.Task
import org.apache.spark.shuffle.FetchFailedException
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.TaskResultBlockId
import org.apache.spark.unsafe.memory.TaskMemoryManager
import org.apache.spark.util._

/**
 * Spark executor, backed by a threadpool to run tasks.
 * 执行计算任务线程,主要负责任务的执行以及Worker,Driver App信息同步
 * This can be used with Mesos, YARN, and the standalone scheduler.
 * An internal RPC interface (at the moment Akka) is used for communication with the driver,
 * except in the case of Mesos fine-grained mode.
 */
private[spark] class Executor(
    executorId: String,
    executorHostname: String,
    env: SparkEnv,
    userClassPath: Seq[URL] = Nil,
    isLocal: Boolean = false)
  extends Logging {

  logInfo(s"Starting executor ID $executorId on host $executorHostname")

  // Application dependencies (added through SparkContext) that we've fetched so far on this node.
  // Each map holds the master's timestamp for the version of that file or JAR we got.
  // HashMap保存Master的文件或JAR,Key文件名,value=>文件修改的时间戳,记录每个文件版本
  private val currentFiles: HashMap[String, Long] = new HashMap[String, Long]()
  private val currentJars: HashMap[String, Long] = new HashMap[String, Long]()

  private val EMPTY_BYTE_BUFFER = ByteBuffer.wrap(new Array[Byte](0))

  private val conf = env.conf

  // No ip or host:port - just hostname
  Utils.checkHost(executorHostname, "Expected executed slave to be a hostname")
  // must not have port specified.
  assert (0 == Utils.parseHostPort(executorHostname)._2)//

  // Make sure the local hostname we report matches the cluster scheduler's name for this host
  Utils.setCustomHostname(executorHostname)

  if (!isLocal) {
    // Setup an uncaught exception handler for non-local mode.
    // Make any thread terminations due to uncaught exceptions kill the entire
    // executor process to avoid surprising stalls.
    Thread.setDefaultUncaughtExceptionHandler(SparkUncaughtExceptionHandler)
  }

  // Start worker thread pool
  //创建Executor执行Task的线程池
  private val threadPool = ThreadUtils.newDaemonCachedThreadPool("Executor task launch worker")
  //用于测量系统
  private val executorSource = new ExecutorSource(threadPool, executorId)
  //非本地模块,注册executorSource
  if (!isLocal) {
    env.metricsSystem.registerSource(executorSource)
    env.blockManager.initialize(conf.getAppId)
  }

  // Create an RpcEndpoint for receiving RPCs from the driver
  //创建注册executorEndpoint负责接收driver消息
  private val executorEndpoint = env.rpcEnv.setupEndpoint(
    ExecutorEndpoint.EXECUTOR_ENDPOINT_NAME, new ExecutorEndpoint(env.rpcEnv, executorId))

  // Whether(可能的选择) to load classes in user jars before those in Spark jars
  private val userClassPathFirst = conf.getBoolean("spark.executor.userClassPathFirst", false)

  // Create our ClassLoader
  // do this after SparkEnv creation so can access the SecurityManager
  //创建一个classloader
  private val urlClassLoader = createClassLoader()
  private val replClassLoader = addReplClassLoaderIfNeeded(urlClassLoader)
  
  // Set the classloader for serializer
  env.serializer.setDefaultClassLoader(replClassLoader)

  // Akka's message frame size. If task result is bigger than this, we use the block manager
  // to send the result back.
  //Akka发送消息的帧大小
  private val akkaFrameSize = AkkaUtils.maxFrameSizeBytes(conf)
  //限制结果总大小的字节
  // Limit of bytes for total size of results (default is 1GB)
  private val maxResultSize = Utils.getMaxResultSize(conf)
  //运行Task列表
  // Maintains the list of running tasks.
  private val runningTasks = new ConcurrentHashMap[Long, TaskRunner]
  // Executor for the heartbeat task.
  //ScheduledExecutorService定时周期执行指定的任务,基于时间的延迟,不会由于系统时间的改变发生执行变化
  private val heartbeater = ThreadUtils.newDaemonSingleThreadScheduledExecutor("driver-heartbeater")
  //启动executor心跳线程,此线程用于向Driver发送心跳
  startDriverHeartbeater()

  def launchTask(
      context: ExecutorBackend,
      taskId: Long,
      attemptNumber: Int,
      taskName: String,
      serializedTask: ByteBuffer): Unit = {
    //实例化一个TaskRunner对象来执行Task  
    val tr = new TaskRunner(context, taskId = taskId, attemptNumber = attemptNumber, taskName,
      serializedTask)
    //将TaskRunner加入到正在运行的Task队列,taskId-->TaskRunner
    runningTasks.put(taskId, tr)
    //TaskRunner实现了Runnable接口,最后使用线程池执行TaskRunner
    threadPool.execute(tr)
  }

  def killTask(taskId: Long, interruptThread: Boolean): Unit = {
    val tr = runningTasks.get(taskId)
    if (tr != null) {
      tr.kill(interruptThread)
    }
  }

  def stop(): Unit = {
    env.metricsSystem.report()
    env.rpcEnv.stop(executorEndpoint)
    heartbeater.shutdown()//线程池就会不再接受任务。
    heartbeater.awaitTermination(10, TimeUnit.SECONDS)//等待关闭线程池，每次等待的超时时间为10秒
    threadPool.shutdown()//线程池就会不再接受任务。
    if (!isLocal) {
      env.stop()
    }
  }

  /** 
   *  Returns the total amount of time this JVM process has spent in garbage collection. 
   *  返回JVM垃圾收集花费总时间
   *  */
  private def computeTotalGcTime(): Long = {
    ManagementFactory.getGarbageCollectorMXBeans.map(_.getCollectionTime).sum
  }

  class TaskRunner(
      execBackend: ExecutorBackend,
      val taskId: Long,//taskId
      val attemptNumber: Int,//重试次数
      taskName: String,//task名称
      serializedTask: ByteBuffer)//Task序列化
    extends Runnable {

    /** 
     *  Whether this task has been killed.
     *  是否这项任务已被杀死
     *   */
    @volatile private var killed = false

    /** 
     *  How much the JVM process has spent in GC when the task starts to run. 
     *  多少的JVM进程一直在GC当任务开始运行  
     *  */
    @volatile var startGCTime: Long = _

    /**
     * The task to run. This will be set in run() by deserializing the task binary coming
     * from the driver. Once it is set, it will never be changed.
     * 运行Task
     */
    @volatile var task: Task[Any] = _

    def kill(interruptThread: Boolean): Unit = {
      logInfo(s"Executor is trying to kill $taskName (TID $taskId)")
      killed = true
      if (task != null) {
        task.kill(interruptThread)
      }
    }

    override def run(): Unit = {
      //Task创建内存管理器  
      val taskMemoryManager = new TaskMemoryManager(env.executorMemoryManager)
      //记录反序列化时间  
      val deserializeStartTime = System.currentTimeMillis()
      //加载具体类时需要用到ClassLoader  
      Thread.currentThread.setContextClassLoader(replClassLoader)
      //创建序列化器  
      val ser = env.closureSerializer.newInstance()
      logInfo(s"Running $taskName (TID $taskId)")
      //调用ExecutorBackend#statusUpdate向Driver发信息汇报当前TaskState.RUNNING状态  
      execBackend.statusUpdate(taskId, TaskState.RUNNING, EMPTY_BYTE_BUFFER)
      //记录运行时间和GC信息  
      var taskStart: Long = 0
      startGCTime = computeTotalGcTime()

      try {
        //反序列化Task的依赖，得到的结果中有taskFile(运行的文件),taskJar(环境依 赖),taskBytes(相当于缓冲池)  
        val (taskFiles, taskJars, taskBytes) = Task.deserializeWithDependencies(serializedTask)
        //下载Task运行缺少的依赖
        updateDependencies(taskFiles, taskJars)
        //反序列化为Task实例
        task = ser.deserialize[Task[Any]](taskBytes, Thread.currentThread.getContextClassLoader)
        //设置Task运行时的MemoryManager  
        task.setTaskMemoryManager(taskMemoryManager)
        // If this task has been killed before we deserialized it, let's quit now. Otherwise,
        // continue executing the task.
        //如果Task在序列化前就已经被killed,则会抛出异常,否则,正常执行  
        if (killed) {
          // Throw an exception rather than returning, because returning within a try{} block
          // causes a NonLocalReturnControl exception to be thrown. The NonLocalReturnControl
          // exception will be caught by the catch block, leading to an incorrect ExceptionFailure
          // for the task.
          throw new TaskKilledException
        }

        logDebug("Task " + taskId + "'s epoch is " + task.epoch)
        env.mapOutputTracker.updateEpoch(task.epoch)

        // Run the actual task and measure its runtime.
        //运行的实际任务，并测量它的运行时间。  
        taskStart = System.currentTimeMillis()
        var threwException = true        
        val (value, accumUpdates) = try {
          //调用task#run方法，得到task运行的结果  
          val res = task.run(
            taskAttemptId = taskId,
            attemptNumber = attemptNumber,
            metricsSystem = env.metricsSystem)
          threwException = false //异常为false
          res
        } finally {
         //清理所有分配的内存和分页,并检测是否有内存泄漏  
          val freedMemory = taskMemoryManager.cleanUpAllAllocatedMemory()
          if (freedMemory > 0) {
            val errMsg = s"Managed memory leak detected; size = $freedMemory bytes, TID = $taskId"
            if (conf.getBoolean("spark.unsafe.exceptionOnMemoryLeak", false) && !threwException) {
              throw new SparkException(errMsg)
            } else {
              logError(errMsg)
            }
          }
        }
        //记录Task完成时间  
        val taskFinish = System.currentTimeMillis()

        // If the task has been killed, let's fail it.
        //如果Task killed,则报错 
        if (task.killed) {
          throw new TaskKilledException
        }
        //否则序列化得到的Task执行的结果  
        val resultSer = env.serializer.newInstance()
        val beforeSerialization = System.currentTimeMillis()
        val valueBytes = resultSer.serialize(value)
        val afterSerialization = System.currentTimeMillis()
        //计量统计
        for (m <- task.metrics) {
          // Deserialization happens in two parts: first, we deserialize a Task object, which
          // includes the Partition. Second, Task.run() deserializes the RDD and function to be run.
          //Executor反序列化消耗的时间
          m.setExecutorDeserializeTime(
            (taskStart - deserializeStartTime) + task.executorDeserializeTime)
          // We need to subtract Task.run()'s deserialization time to avoid double-counting
          //实际执行任务消耗时间
          m.setExecutorRunTime((taskFinish - taskStart) - task.executorDeserializeTime)
          //执行垃圾回收消耗的时间
          m.setJvmGCTime(computeTotalGcTime() - startGCTime)
          //执行结果序列化消耗的时间
          m.setResultSerializationTime(afterSerialization - beforeSerialization)         
          m.updateAccumulators()
        }
        //创建直接返回给Driver的结果对象DirectTaskResult  
        val directResult = new DirectTaskResult(valueBytes, accumUpdates, task.metrics.orNull)
        //简单序列化
        val serializedDirectResult = ser.serialize(directResult)
        val resultSize = serializedDirectResult.limit

        // directSend = sending directly back to the driver
        //对直接返回的结果对象大小进行判断  
        val serializedResult: ByteBuffer = {
          if (maxResultSize > 0 && resultSize > maxResultSize) {
            // 如果结果的大小大于1GB，那么直接丢弃，
            // 可以在spark.driver.maxResultSize设置
            logWarning(s"Finished $taskName (TID $taskId). Result is larger than maxResultSize " +
              s"(${Utils.bytesToString(resultSize)} > ${Utils.bytesToString(maxResultSize)}), " +
              s"dropping it.")
            ser.serialize(new IndirectTaskResult[Any](TaskResultBlockId(taskId), resultSize))
          } else if (resultSize >= akkaFrameSize - AkkaUtils.reservedSizeBytes) {
             //结果大小大于设定的阀值，则放入BlockManager中   
            val blockId = TaskResultBlockId(taskId)
            env.blockManager.putBytes(
              blockId, serializedDirectResult, StorageLevel.MEMORY_AND_DISK_SER)
            logInfo(
              s"Finished $taskName (TID $taskId). $resultSize bytes result sent via BlockManager)")
             //返回给Driver的对象IndirectTaskResult  
            ser.serialize(new IndirectTaskResult[Any](blockId, resultSize))
          } else {
             //结果可以直接回传到Driver
            logInfo(s"Finished $taskName (TID $taskId). $resultSize bytes result sent to driver")
            serializedDirectResult
          }
        }
        //通过AKKA向Driver汇报本次Task的已经完成,更新状态
        execBackend.statusUpdate(taskId, TaskState.FINISHED, serializedResult)

      } catch {
        case ffe: FetchFailedException =>
          val reason = ffe.toTaskEndReason
          //如果失败,则更新任务失败
          execBackend.statusUpdate(taskId, TaskState.FAILED, ser.serialize(reason))

        case _: TaskKilledException | _: InterruptedException if task.killed =>
          logInfo(s"Executor killed $taskName (TID $taskId)")
          execBackend.statusUpdate(taskId, TaskState.KILLED, ser.serialize(TaskKilled))

        case cDE: CommitDeniedException =>
          val reason = cDE.toTaskEndReason
          execBackend.statusUpdate(taskId, TaskState.FAILED, ser.serialize(reason))

        case t: Throwable =>
          // Attempt to exit cleanly by informing the driver of our failure.
          // If anything goes wrong (or this was a fatal exception), we will delegate to
          // the default uncaught exception handler, which will terminate the Executor.
          logError(s"Exception in $taskName (TID $taskId)", t)
          val metrics: Option[TaskMetrics] = Option(task).flatMap { task =>
            task.metrics.map { m =>
              m.setExecutorRunTime(System.currentTimeMillis() - taskStart)
              m.setJvmGCTime(computeTotalGcTime() - startGCTime)
              m.updateAccumulators()
              m
            }
          }
          val serializedTaskEndReason = {
            try {
              ser.serialize(new ExceptionFailure(t, metrics))
            } catch {
              case _: NotSerializableException =>
                // t is not serializable so just send the stacktrace
                ser.serialize(new ExceptionFailure(t, metrics, false))
            }
          }
          execBackend.statusUpdate(taskId, TaskState.FAILED, serializedTaskEndReason)

          // Don't forcibly exit unless the exception was inherently fatal, to avoid
          // stopping other tasks unnecessarily.
          if (Utils.isFatalError(t)) {
            SparkUncaughtExceptionHandler.uncaughtException(t)
          }

      } finally {
        //将当前Task从runningTasks中删除
        runningTasks.remove(taskId)
      }
    }
  }

  /**
   * Create a ClassLoader for use in tasks, adding any JARs specified by the user or any classes
   * created by the interpreter to the search path
   * 创建一个用于任务的类加载器,添加用户指定的类或类到搜索路径中
   */
  private def createClassLoader(): MutableURLClassLoader = {
    // Bootstrap the list of jars with the user class path.
    val now = System.currentTimeMillis()
    userClassPath.foreach { url =>
      currentJars(url.getPath().split("/").last) = now
    }

    val currentLoader = Utils.getContextOrSparkClassLoader

    // For each of the jars in the jarSet, add them to the class loader.
    // We assume each of the files has already been fetched.
    val urls = userClassPath.toArray ++ currentJars.keySet.map { uri =>
      new File(uri.split("/").last).toURI.toURL
    }
    if (userClassPathFirst) {
      new ChildFirstURLClassLoader(urls, currentLoader)
    } else {
      new MutableURLClassLoader(urls, currentLoader)
    }
  }

  /**
   * If the REPL is in use, add another ClassLoader that will read
   * new classes defined by the REPL as the user types code
   */
  private def addReplClassLoaderIfNeeded(parent: ClassLoader): ClassLoader = {
    val classUri = conf.get("spark.repl.class.uri", null)
    if (classUri != null) {
      logInfo("Using REPL class URI: " + classUri)
      try {
        val _userClassPathFirst: java.lang.Boolean = userClassPathFirst
        val klass = Utils.classForName("org.apache.spark.repl.ExecutorClassLoader")
          .asInstanceOf[Class[_ <: ClassLoader]]
        val constructor = klass.getConstructor(classOf[SparkConf], classOf[String],
          classOf[ClassLoader], classOf[Boolean])
        constructor.newInstance(conf, classUri, parent, _userClassPathFirst)
      } catch {
        case _: ClassNotFoundException =>
          logError("Could not find org.apache.spark.repl.ExecutorClassLoader on classpath!")
          System.exit(1)
          null
      }
    } else {
      parent
    }
  }

  /**
   * Download any missing dependencies if we receive a new set of files and JARs from the
   * SparkContext. Also adds any new JARs we fetched to the class loader.
   * 获取依赖是利用Utils.fetchFile方法实现,下载jar文件还会添加到Executor自身类加载器的URL中
   */
  private def updateDependencies(newFiles: HashMap[String, Long], newJars: HashMap[String, Long]) {
    lazy val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
    synchronized {
      // Fetch missing dependencies
      //获取依赖是利用Utils.fetchFile方法实现
      for ((name, timestamp) <- newFiles if currentFiles.getOrElse(name, -1L) < timestamp) {
        logInfo("Fetching " + name + " with timestamp " + timestamp)
        // Fetch file with useCache mode, close cache for local mode.
        //获取文件缓存模式,关闭本地缓存模式\\
        Utils.fetchFile(name, new File(SparkFiles.getRootDirectory()), conf,
          env.securityManager, hadoopConf, timestamp, useCache = !isLocal)
        currentFiles(name) = timestamp//文件名,文件的最新修改时间
      }
      for ((name, timestamp) <- newJars) {
        val localName = name.split("/").last
        val currentTimeStamp = currentJars.get(name)
          .orElse(currentJars.get(localName))
          .getOrElse(-1L)
        if (currentTimeStamp < timestamp) {
          logInfo("Fetching " + name + " with timestamp " + timestamp)
          // Fetch file with useCache mode, close cache for local mode.
          Utils.fetchFile(name, new File(SparkFiles.getRootDirectory()), conf,
            env.securityManager, hadoopConf, timestamp, useCache = !isLocal)
          currentJars(name) = timestamp
          // Add it to our class loader
          val url = new File(SparkFiles.getRootDirectory(), localName).toURI.toURL
          if (!urlClassLoader.getURLs().contains(url)) {
            logInfo("Adding " + url + " to class loader")
            //下载的jar文件还会添加到Executor自身加载器的URL中
            urlClassLoader.addURL(url)
          }
        }
      }
    }
  }

  private val heartbeatReceiverRef =
    RpcUtils.makeDriverRef(HeartbeatReceiver.ENDPOINT_NAME, conf, env.rpcEnv)

  /** 
   *  Reports heartbeat and metrics for active tasks to the driver. 
   *  报告心跳和测量活动任务到Driver。
   **/
  private def reportHeartBeat(): Unit = {
    // list of (task id, metrics) to send back to the driver
    //发送任务列表
    val tasksMetrics = new ArrayBuffer[(Long, TaskMetrics)]()
    val curGCTime = computeTotalGcTime()//
    
    for (taskRunner <- runningTasks.values()) {
      if (taskRunner.task != null) {
        //更新正在处理的任务的测量信息
        taskRunner.task.metrics.foreach { metrics =>
          metrics.updateShuffleReadMetrics()
          metrics.updateInputMetrics()
          metrics.setJvmGCTime(curGCTime - taskRunner.startGCTime)
          metrics.updateAccumulators()

          if (isLocal) {
            // JobProgressListener will hold an reference of it during
            // onExecutorMetricsUpdate(), then JobProgressListener can not see
            // the changes of metrics any more, so make a deep copy of it
            val copiedMetrics = Utils.deserialize[TaskMetrics](Utils.serialize(metrics))
            tasksMetrics += ((taskRunner.taskId, copiedMetrics))
          } else {
            // It will be copied by serialization
            tasksMetrics += ((taskRunner.taskId, metrics))
          }
        }
      }
    }
    //通知blockManagerMaster,此Executor上的blockManager依然活着,
    //发送HeartbeatResponse消息到HeartbeatReceiver.receiveAndReply
    val message = Heartbeat(executorId, tasksMetrics.toArray, env.blockManager.blockManagerId)
    try {
      //发送消息给RpcEndpoint.receive并在默认的超时内得到结果
      val response = heartbeatReceiverRef.askWithRetry[HeartbeatResponse](message)
      if (response.reregisterBlockManager) {
        logInfo("Told to re-register on heartbeat")
        env.blockManager.reregister()
      }
    } catch {
      case NonFatal(e) => logWarning("Issue communicating with driver in heartbeater", e)
    }
  }

  /**
   * Schedules a task to report heartbeat and partial metrics for active tasks to driver.
   * Executor心跳由startDriverHeartbeater启动
   */
  private def startDriverHeartbeater(): Unit = {
    //Executor和Driver之间心跳的间隔,心跳线程的间隔默认10秒,即BlockManager超时时间
    //m毫秒,s秒
    val intervalMs = conf.getTimeAsMs("spark.executor.heartbeatInterval", "10s")
    //心跳不能发送时的随机间隔,每次随机时间10---20秒
    // Wait a random interval so the heartbeats don't end up in sync
    val initialDelay = intervalMs + (math.random * intervalMs).asInstanceOf[Int]

    val heartbeatTask = new Runnable() {
      override def run(): Unit = Utils.logUncaughtExceptions(reportHeartBeat())
    }
    /**
     * schedule和scheduleAtFixedRate的区别在于：如果指定开始执行的时间在当前系统运行时间之前，
     * scheduleAtFixedRate会把已经过去的时间也作为周期执行，而schedule不会把过去的时间算上。
     */
    heartbeater.scheduleAtFixedRate(heartbeatTask, initialDelay, intervalMs, TimeUnit.MILLISECONDS)
  }

 
}
