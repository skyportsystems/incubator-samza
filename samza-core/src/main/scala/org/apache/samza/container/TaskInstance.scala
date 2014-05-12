/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.container

import org.apache.samza.metrics.MetricsReporter
import org.apache.samza.config.Config
import org.apache.samza.Partition
import grizzled.slf4j.Logging
import scala.collection.JavaConversions._
import org.apache.samza.storage.TaskStorageManager
import org.apache.samza.config.StreamConfig.Config2Stream
import org.apache.samza.system.SystemStreamPartition
import org.apache.samza.task.TaskContext
import org.apache.samza.task.ClosableTask
import org.apache.samza.task.InitableTask
import org.apache.samza.system.IncomingMessageEnvelope
import org.apache.samza.task.WindowableTask
import org.apache.samza.checkpoint.CheckpointManager
import org.apache.samza.task.TaskLifecycleListener
import org.apache.samza.task.StreamTask
import org.apache.samza.system.SystemStream
import org.apache.samza.checkpoint.Checkpoint
import org.apache.samza.task.ReadableCollector
import org.apache.samza.system.SystemConsumers
import org.apache.samza.system.SystemProducers
import org.apache.samza.task.ReadableCoordinator
import org.apache.samza.metrics.Gauge
import org.apache.samza.checkpoint.OffsetManager
import org.apache.samza.SamzaException

class TaskInstance(
  task: StreamTask,
  partition: Partition,
  config: Config,
  metrics: TaskInstanceMetrics,
  consumerMultiplexer: SystemConsumers,
  producerMultiplexer: SystemProducers,
  offsetManager: OffsetManager = new OffsetManager,
  storageManager: TaskStorageManager = null,
  reporters: Map[String, MetricsReporter] = Map(),
  listeners: Seq[TaskLifecycleListener] = Seq(),
  inputStreams: Set[SystemStream] = Set(),
  windowMs: Long = -1,
  commitMs: Long = 60000,
  clock: () => Long = { System.currentTimeMillis },
  collector: ReadableCollector = new ReadableCollector) extends Logging {

  var lastWindowMs = 0L
  var lastCommitMs = 0L
  val isInitableTask = task.isInstanceOf[InitableTask]
  val isWindowableTask = task.isInstanceOf[WindowableTask]
  val isClosableTask = task.isInstanceOf[ClosableTask]
  val context = new TaskContext {
    def getMetricsRegistry = metrics.registry
    def getPartition = partition
    def getStore(storeName: String) = if (storageManager != null) {
      storageManager(storeName)
    } else {
      warn("No store found for name: %s" format storeName)

      null
    }
  }

  def registerMetrics {
    debug("Registering metrics for partition: %s." format partition)

    reporters.values.foreach(_.register(metrics.source, metrics.registry))
  }

  def registerOffsets {
    debug("Registering offsets for partition: %s." format partition)

    inputStreams.foreach(systemStream => {
      offsetManager.register(new SystemStreamPartition(systemStream, partition))
    })
  }

  def startStores {
    if (storageManager != null) {
      debug("Starting storage manager for partition: %s." format partition)

      storageManager.init
    } else {
      debug("Skipping storage manager initialization for partition: %s." format partition)
    }
  }

  def initTask {
    listeners.foreach(_.beforeInit(config, context))

    if (isInitableTask) {
      debug("Initializing task for partition: %s." format partition)

      task.asInstanceOf[InitableTask].init(config, context)
    } else {
      debug("Skipping task initialization for partition: %s." format partition)
    }

    listeners.foreach(_.afterInit(config, context))
  }

  def registerProducers {
    debug("Registering producers for partition: %s." format partition)

    producerMultiplexer.register(metrics.source)
  }

  def registerConsumers {
    debug("Registering consumers for partition: %s." format partition)

    inputStreams.foreach(systemStream => {
      val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
      val offset = offsetManager.getStartingOffset(systemStreamPartition)
        .getOrElse(throw new SamzaException("No offset defined for partition %s: %s" format (partition, systemStream)))
      consumerMultiplexer.register(systemStreamPartition, offset)
      metrics.addOffsetGauge(systemStream, () => {
        offsetManager
          .getLastProcessedOffset(systemStreamPartition)
          .getOrElse(null)
      })
    })
  }

  def process(envelope: IncomingMessageEnvelope, coordinator: ReadableCoordinator) {
    metrics.processes.inc

    listeners.foreach(_.beforeProcess(envelope, config, context))

    trace("Processing incoming message envelope for partition: %s, %s" format (partition, envelope.getSystemStreamPartition))

    task.process(envelope, collector, coordinator)

    listeners.foreach(_.afterProcess(envelope, config, context))

    trace("Updating offset map for partition: %s, %s, %s" format (partition, envelope.getSystemStreamPartition, envelope.getOffset))

    offsetManager.update(envelope.getSystemStreamPartition, envelope.getOffset)
  }

  def window(coordinator: ReadableCoordinator) {
    if (isWindowableTask && windowMs >= 0 && lastWindowMs + windowMs < clock()) {
      trace("Windowing for partition: %s" format partition)

      metrics.windows.inc

      lastWindowMs = clock()
      task.asInstanceOf[WindowableTask].window(collector, coordinator)

      trace("Assigned last window time for partition: %s, %s" format (partition, lastWindowMs))
    } else {
      trace("Skipping window for partition: %s" format partition)

      metrics.windowsSkipped.inc
    }
  }

  def send {
    if (collector.envelopes.size > 0) {
      trace("Sending messages for partition: %s, %s" format (partition, collector.envelopes.size))

      metrics.sends.inc
      metrics.messagesSent.inc(collector.envelopes.size)

      collector.envelopes.foreach(envelope => producerMultiplexer.send(metrics.source, envelope))

      trace("Resetting collector for partition: %s" format partition)

      collector.reset
    } else {
      trace("Skipping send for partition %s because no messages were collected." format partition)

      metrics.sendsSkipped.inc
    }
  }

  def commit(coordinator: ReadableCoordinator) {
    if (lastCommitMs + commitMs < clock() || coordinator.isCommitRequested || coordinator.isShutdownRequested) {
      trace("Flushing state stores for partition: %s" format partition)

      metrics.commits.inc

      lastCommitMs = clock()

      storageManager.flush

      trace("Flushing producers for partition: %s" format partition)

      producerMultiplexer.flush(metrics.source)

      trace("Committing offset manager for partition: %s" format partition)

      offsetManager.checkpoint(partition)
    } else {
      trace("Skipping commit for partition: %s" format partition)

      metrics.commitsSkipped.inc
    }
  }

  def shutdownTask {
    listeners.foreach(_.beforeClose(config, context))

    if (task.isInstanceOf[ClosableTask]) {
      debug("Shutting down stream task for partition: %s" format partition)

      task.asInstanceOf[ClosableTask].close
    } else {
      debug("Skipping stream task shutdown for partition: %s" format partition)
    }

    listeners.foreach(_.afterClose(config, context))
  }

  def shutdownStores {
    if (storageManager != null) {
      debug("Shutting down storage manager for partition: %s" format partition)

      storageManager.stop
    } else {
      debug("Skipping storage manager shutdown for partition: %s" format partition)
    }
  }

  override def toString() = "TaskInstance for class %s and partition %s." format (task.getClass.getName, partition)

  def toDetailedString() = "TaskInstance [windowable=%s, window_time=%s, commit_time=%s, closable=%s, collector_size=%s]" format (isWindowableTask, lastWindowMs, lastCommitMs, isClosableTask, collector.envelopes.size)

}
