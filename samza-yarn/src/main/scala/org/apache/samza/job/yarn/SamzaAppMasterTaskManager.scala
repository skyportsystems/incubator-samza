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

package org.apache.samza.job.yarn

import java.nio.ByteBuffer
import java.util.Collections

import scala.collection.JavaConversions._

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.DataOutputBuffer
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.yarn.api.ApplicationConstants
import org.apache.hadoop.yarn.api.protocolrecords.StartContainerRequest
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest
import org.apache.hadoop.yarn.client.api.NMClient
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier
import org.apache.hadoop.yarn.util.ConverterUtils
import org.apache.hadoop.yarn.util.Records
import org.apache.samza.config.Config
import org.apache.samza.config.TaskConfig.Config2Task
import org.apache.samza.config.YarnConfig
import org.apache.samza.config.YarnConfig.Config2Yarn
import org.apache.samza.job.CommandBuilder
import org.apache.samza.job.ShellCommandBuilder
import org.apache.samza.util.Util

import grizzled.slf4j.Logging

object SamzaAppMasterTaskManager {
  val DEFAULT_CONTAINER_MEM = 1024
  val DEFAULT_CPU_CORES = 1
  val DEFAULT_CONTAINER_RETRY_COUNT = 8
  val DEFAULT_CONTAINER_RETRY_WINDOW_MS = 300000
}

case class TaskFailure(val count: Int, val lastFailure: Long)

/**
 * Samza's application master is mostly interested in requesting containers to
 * run Samza jobs. SamzaAppMasterTaskManager is responsible for requesting new
 * containers, handling failures, and notifying the application master that the
 * job is done.
 */
class SamzaAppMasterTaskManager(clock: () => Long, config: Config, state: SamzaAppMasterState, amClient: AMRMClientAsync[ContainerRequest], conf: YarnConfiguration) extends YarnAppMasterListener with Logging {
  import SamzaAppMasterTaskManager._

  state.taskCount = config.getTaskCount match {
    case Some(count) => count
    case None =>
      info("No %s specified. Defaulting to one container." format YarnConfig.TASK_COUNT)
      1
  }

  val allSystemStreamPartitions = Util.getInputStreamPartitions(config)
  var taskFailures = Map[Int, TaskFailure]()
  var tooManyFailedContainers = false
  // TODO we might want to use NMClientAsync as well
  var containerManager: NMClient = null

  override def shouldShutdown = state.completedTasks == state.taskCount || tooManyFailedContainers

  override def onInit() {
    state.neededContainers = state.taskCount
    state.unclaimedTasks = (0 until state.taskCount).toSet
    containerManager = NMClient.createNMClient()
    containerManager.init(conf)
    containerManager.start

    info("Requesting %s containers" format state.taskCount)

    requestContainers(config.getContainerMaxMemoryMb.getOrElse(DEFAULT_CONTAINER_MEM), config.getContainerMaxCpuCores.getOrElse(DEFAULT_CPU_CORES), state.neededContainers)
  }

  override def onShutdown {
    if (containerManager != null) {
      containerManager.stop
    }
  }

  override def onContainerAllocated(container: Container) {
    val containerIdStr = ConverterUtils.toString(container.getId)

    info("Got a container from YARN ResourceManager: %s" format container)

    state.unclaimedTasks.headOption match {
      case Some(taskId) => {
        info("Got available task id (%d) for container: %s" format (taskId, container))
        val streamsAndPartitionsForTask = Util.getStreamsAndPartitionsForContainer(taskId, state.taskCount, allSystemStreamPartitions)
        info("Claimed partitions %s for container ID %s" format (streamsAndPartitionsForTask, taskId))
        val cmdBuilderClassName = config.getCommandClass.getOrElse(classOf[ShellCommandBuilder].getName)
        val cmdBuilder = Class.forName(cmdBuilderClassName).newInstance.asInstanceOf[CommandBuilder]
          .setConfig(config)
          .setName("samza-container-%s" format taskId)
          .setStreamPartitions(streamsAndPartitionsForTask)
        val command = cmdBuilder.buildCommand
        info("Task ID %s using command %s" format (taskId, command))
        val env = cmdBuilder.buildEnvironment.map { case (k, v) => (k, Util.envVarEscape(v)) }
        info("Task ID %s using env %s" format (taskId, env))
        val path = new Path(config.getPackagePath.get)
        info("Starting task ID %s using package path %s" format (taskId, path))

        startContainer(
          path,
          container,
          env.toMap,
          "export SAMZA_LOG_DIR=%s && ln -sfn %s logs && exec ./__package/%s 1>logs/%s 2>logs/%s" format (ApplicationConstants.LOG_DIR_EXPANSION_VAR, ApplicationConstants.LOG_DIR_EXPANSION_VAR, command, ApplicationConstants.STDOUT, ApplicationConstants.STDERR))

        state.neededContainers -= 1
        state.runningTasks += taskId -> container
        state.unclaimedTasks -= taskId
        state.taskPartitions += taskId -> streamsAndPartitionsForTask.map(_.getPartition).toSet

        info("Claimed task ID %s for container %s on node %s (http://%s/node/containerlogs/%s)." format (taskId, containerIdStr, container.getNodeId.getHost, container.getNodeHttpAddress, containerIdStr))

        info("Started task ID %s" format taskId)
      }
      case _ => {
        // there are no more tasks to run, so release the container
        info("Got an extra container from YARN ResourceManager: %s" format (container))

        amClient.releaseAssignedContainer(container.getId)
      }
    }
  }

  override def onContainerCompleted(containerStatus: ContainerStatus) {
    val containerIdStr = ConverterUtils.toString(containerStatus.getContainerId)
    val taskId = state.runningTasks.filter { case (_, container) => container.getId().equals(containerStatus.getContainerId()) }.keys.headOption

    taskId match {
      case Some(taskId) => {
        state.runningTasks -= taskId
        state.taskPartitions -= taskId
      }
      case _ => None
    }

    containerStatus.getExitStatus match {
      case 0 => {
        info("Container %s completed successfully." format containerIdStr)

        state.completedTasks += 1

        if (taskId.isDefined) {
          state.finishedTasks += taskId.get
          taskFailures -= taskId.get
        }

        if (state.completedTasks == state.taskCount) {
          info("Setting job status to SUCCEEDED, since all tasks have been marked as completed.")
          state.status = FinalApplicationStatus.SUCCEEDED
        }
      }
      case -100 => {
        info("Got an exit code of -100. This means that container %s was "
          + "killed by YARN, either due to being released by the application "
          + "master or being 'lost' due to node failures etc." format containerIdStr)

        state.releasedContainers += 1

        // If this container was assigned some partitions (a containerId), then 
        // clean up, and request a new container for the tasks. This only 
        // should happen if the container was 'lost' due to node failure, not 
        // if the AM released the container.
        if (taskId.isDefined) {
          info("Released container %s was assigned task ID %s. Requesting a new container for the task." format (containerIdStr, taskId.get))

          state.neededContainers += 1
          state.unclaimedTasks += taskId.get

          // request a new container
          requestContainers(config.getContainerMaxMemoryMb.getOrElse(DEFAULT_CONTAINER_MEM), config.getContainerMaxCpuCores.getOrElse(DEFAULT_CPU_CORES), 1)
        }
      }
      case _ => {
        info("Container %s failed with exit code %d - %s." format (containerIdStr, containerStatus.getExitStatus, containerStatus.getDiagnostics))

        state.failedContainers += 1

        taskId match {
          case Some(taskId) =>
            info("Failed container %s owned task id %s." format (containerIdStr, taskId))

            state.unclaimedTasks += taskId
            state.neededContainers += 1

            // A container failed for an unknown reason. Let's check to see if 
            // we need to shutdown the whole app master if too many container 
            // failures have happened. The rules for failing are that the failure 
            // count for a task id must be > the configured retry count, and the 
            // last failure (the one prior to this one) must have happened less 
            // than retry window ms ago. If retry count is set to 0, the app 
            // master will fail on any container failure. If the retry count is 
            // set to a number < 0, a container failure will never trigger an 
            // app master failure.
            val retryCount = config.getContainerRetryCount.getOrElse(DEFAULT_CONTAINER_RETRY_COUNT)
            val retryWindowMs = config.getContainerRetryWindowMs.getOrElse(DEFAULT_CONTAINER_RETRY_WINDOW_MS)

            if (retryCount == 0) {
              error("Task id %s (%s) failed, and retry count is set to 0, so shutting down the application master, and marking the job as failed."
                format (taskId, containerIdStr))

              tooManyFailedContainers = true
            } else if (retryCount > 0) {
              val (currentFailCount, lastFailureTime) = taskFailures.get(taskId) match {
                case Some(TaskFailure(count, lastFailure)) => (count + 1, lastFailure)
                case _ => (1, 0L)
              }

              if (currentFailCount > retryCount) {
                val lastFailureMsDiff = clock() - lastFailureTime

                if (lastFailureMsDiff < retryWindowMs) {
                  error("Task id %s (%s) has failed %s times, with last failure %sms ago. This is greater than retry count of %s and window of %s, so shutting down the application master, and marking the job as failed."
                    format (taskId, containerIdStr, currentFailCount, lastFailureMsDiff, retryCount, retryWindowMs))

                  // We have too many failures, and we're within the window 
                  // boundary, so reset shut down the app master.
                  tooManyFailedContainers = true
                  state.status = FinalApplicationStatus.FAILED
                } else {
                  info("Resetting fail count for task id %s back to 1, since last container failure (%s) for this task id was outside the bounds of the retry window."
                    format (taskId, containerIdStr))

                  // Reset counter back to 1, since the last failure for this 
                  // container happened outside the window boundary.
                  taskFailures += taskId -> TaskFailure(1, clock())
                }
              } else {
                info("Current fail count for task id %s is %s." format (taskId, currentFailCount))
                taskFailures += taskId -> TaskFailure(currentFailCount, clock())
              }
            }

            if (!tooManyFailedContainers) {
              // Request a new container
              requestContainers(config.getContainerMaxMemoryMb.getOrElse(DEFAULT_CONTAINER_MEM), config.getContainerMaxCpuCores.getOrElse(DEFAULT_CPU_CORES), 1)
            }
          case _ => None
        }
      }
    }
  }

  protected def startContainer(packagePath: Path, container: Container, env: Map[String, String], cmds: String*) {
    info("starting container %s %s %s %s" format (packagePath, container, env, cmds))

    // set the local package so that the containers and app master are provisioned with it
    val packageResource = Records.newRecord(classOf[LocalResource])
    val packageUrl = ConverterUtils.getYarnUrlFromPath(packagePath)
    val fileStatus = packagePath.getFileSystem(conf).getFileStatus(packagePath)

    packageResource.setResource(packageUrl)
    packageResource.setSize(fileStatus.getLen)
    packageResource.setTimestamp(fileStatus.getModificationTime)
    packageResource.setType(LocalResourceType.ARCHIVE)
    packageResource.setVisibility(LocalResourceVisibility.APPLICATION)

    // copy tokens (copied from dist shell example)
    val credentials = UserGroupInformation.getCurrentUser.getCredentials
    val dob = new DataOutputBuffer
    credentials.writeTokenStorageToStream(dob)
    // now remove the AM->RM token so that containers cannot access it
    val iter = credentials.getAllTokens.iterator
    while (iter.hasNext) {
      val token = iter.next
      if (token.getKind.equals(AMRMTokenIdentifier.KIND_NAME)) {
        iter.remove
      }
    }
    val allTokens = ByteBuffer.wrap(dob.getData, 0, dob.getLength)

    // start the container
    val ctx = Records.newRecord(classOf[ContainerLaunchContext])
    ctx.setEnvironment(env)
    ctx.setTokens(allTokens.duplicate)
    ctx.setCommands(cmds.toList)
    ctx.setLocalResources(Collections.singletonMap("__package", packageResource))

    debug("setting package to %s" format packageResource)
    debug("setting context to %s" format ctx)

    val startContainerRequest = Records.newRecord(classOf[StartContainerRequest])
    startContainerRequest.setContainerLaunchContext(ctx)
    containerManager.startContainer(container, ctx)
  }

  protected def requestContainers(memMb: Int, cpuCores: Int, containers: Int) {
    info("Requesting %d container(s) with %dmb of memory" format (containers, memMb))
    val capability = Records.newRecord(classOf[Resource])
    val priority = Records.newRecord(classOf[Priority])
    priority.setPriority(0)
    capability.setMemory(memMb)
    capability.setVirtualCores(cpuCores)
    (0 until containers).foreach(idx => amClient.addContainerRequest(new ContainerRequest(capability, null, null, priority)))
  }
}
