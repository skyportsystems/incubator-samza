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

import scala.annotation.elidable
import scala.annotation.elidable.ASSERTION

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync.CallbackHandler
import org.apache.hadoop.yarn.client.api.async.impl.AMRMClientAsyncImpl
import org.apache.hadoop.yarn.util.ConverterUtils
import org.apache.samza.SamzaException
import org.junit.Assert._
import org.junit.Test
import org.mockito.Mockito

class TestSamzaAppMasterLifecycle {
  val amClient = new AMRMClientAsyncImpl[ContainerRequest](1, Mockito.mock(classOf[CallbackHandler])) {
    var host = ""
    var port = 0
    var status: FinalApplicationStatus = null
    override def registerApplicationMaster(appHostName: String, appHostPort: Int, appTrackingUrl: String): RegisterApplicationMasterResponse = {
      this.host = appHostName
      this.port = appHostPort
      new RegisterApplicationMasterResponse {
        override def setApplicationACLs(map: java.util.Map[ApplicationAccessType, String]):Unit = ()
        override def getApplicationACLs = null
        override def setMaximumResourceCapability(r: Resource):Unit = ()
        override def getMaximumResourceCapability = new Resource {
          def getMemory = 512
          def getVirtualCores = 2
          def setMemory(memory: Int) {}
          def setVirtualCores(vCores: Int) {}
          def compareTo(o: Resource) = 0
        }
        override def getClientToAMTokenMasterKey = null
        override def setClientToAMTokenMasterKey(buffer: ByteBuffer) {}
      }
    }
    override def unregisterApplicationMaster(appStatus: FinalApplicationStatus,
      appMessage: String,
      appTrackingUrl: String) {
      this.status = appStatus
    }
    override def releaseAssignedContainer(containerId: ContainerId) {}
    override def getClusterNodeCount() = 1

    override def serviceInit(config: Configuration) {}
    override def serviceStart() {}
    override def serviceStop() {}
  }

  @Test
  def testLifecycleShouldRegisterOnInit {
    val state = new SamzaAppMasterState(-1, ConverterUtils.toContainerId("container_1350670447861_0003_01_000001"), "test", 1, 2)
    state.rpcPort = 1
    val saml = new SamzaAppMasterLifecycle(512, 2, state, amClient)
    saml.onInit
    assert(amClient.host == "test")
    assert(amClient.port == 1)
    assertFalse(saml.shouldShutdown)
  }

  @Test
  def testLifecycleShouldUnregisterOnShutdown {
    val state = new SamzaAppMasterState(-1, ConverterUtils.toContainerId("container_1350670447861_0003_01_000001"), "", 1, 2)
    state.status = FinalApplicationStatus.SUCCEEDED
    new SamzaAppMasterLifecycle(128, 1, state, amClient).onShutdown
    assert(amClient.status == FinalApplicationStatus.SUCCEEDED)
  }

  @Test
  def testLifecycleShouldThrowAnExceptionOnReboot {
    var gotException = false
    try {
      new SamzaAppMasterLifecycle(368, 1, null, amClient).onReboot
    } catch {
      // expected
      case e: SamzaException => gotException = true
    }
    assert(gotException)
  }

  @Test
  def testLifecycleShouldShutdownOnInvalidContainerSettings {
    val state = new SamzaAppMasterState(-1, ConverterUtils.toContainerId("container_1350670447861_0003_01_000001"), "test", 1, 2)
    state.rpcPort = 1
    List(new SamzaAppMasterLifecycle(768, 1, state, amClient),
      new SamzaAppMasterLifecycle(368, 3, state, amClient)).map(saml => {
        saml.onInit
        assertTrue(saml.shouldShutdown)
      })
  }
}
