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

package org.apache.spark.rpc.netty

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.control.NonFatal

import org.apache.spark.{SparkException, Logging}
import org.apache.spark.network.client.RpcResponseCallback
import org.apache.spark.rpc._
import org.apache.spark.util.ThreadUtils

private[netty] class Dispatcher(nettyEnv: NettyRpcEnv) extends Logging {

  private class EndpointData(
      val name: String,
      val endpoint: RpcEndpoint,
      val ref: NettyRpcEndpointRef) {
    val inbox = new Inbox(ref, endpoint)
  }

  private val endpoints = new ConcurrentHashMap[String, EndpointData]()
  private val endpointRefs = new ConcurrentHashMap[RpcEndpoint, RpcEndpointRef]()

  // Track the receivers whose inboxes may contain messages.
  private val receivers = new LinkedBlockingQueue[EndpointData]()

  @GuardedBy("this")
  private var stopped = false

  def registerRpcEndpoint(name: String, endpoint: RpcEndpoint): NettyRpcEndpointRef = {
    val addr = new NettyRpcAddress(nettyEnv.address.host, nettyEnv.address.port, name)
    val endpointRef = new NettyRpcEndpointRef(nettyEnv.conf, addr, nettyEnv)
    synchronized {
      if (stopped) {
        throw new IllegalStateException("RpcEnv has been stopped")
      }
      if (endpoints.putIfAbsent(name, new EndpointData(name, endpoint, endpointRef)) != null) {
        throw new IllegalArgumentException(s"There is already an RpcEndpoint called $name")
      }
      val data = endpoints.get(name)
      endpointRefs.put(data.endpoint, data.ref)
      receivers.put(data)
    }
    endpointRef
  }

  def getRpcEndpointRef(endpoint: RpcEndpoint): RpcEndpointRef = endpointRefs.get(endpoint)

  def removeRpcEndpointRef(endpoint: RpcEndpoint): Unit = endpointRefs.remove(endpoint)

  // Should be idempotent
  private def unregisterRpcEndpoint(name: String): Unit = {
    val data = endpoints.remove(name)
    if (data != null) {
      data.inbox.stop()
      receivers.put(data)
    }
    // Don't clean `endpointRefs` here because it's possible that some messages are being processed
    // now and they can use `getRpcEndpointRef`. So `endpointRefs` will be cleaned in Inbox via
    // `removeRpcEndpointRef`.
  }

  def stop(rpcEndpointRef: RpcEndpointRef): Unit = {
    synchronized {
      if (stopped) {
        // This endpoint will be stopped by Dispatcher.stop() method.
        return
      }
      unregisterRpcEndpoint(rpcEndpointRef.name)
    }
  }

  /**
   * Send a message to all registered [[RpcEndpoint]]s.
   * @param message
   */
  def broadcastMessage(message: InboxMessage): Unit = {
    val iter = endpoints.keySet().iterator()
    while (iter.hasNext) {
      val name = iter.next
      postMessageToInbox(name, (_) => message,
        () => { logWarning(s"Drop ${message} because ${name} has been stopped") })
    }
  }

  def postMessage(message: RequestMessage, callback: RpcResponseCallback): Unit = {
    def createMessage(sender: NettyRpcEndpointRef): InboxMessage = {
      val rpcCallContext =
        new RemoteNettyRpcCallContext(
          nettyEnv, sender, callback, message.senderAddress, message.needReply)
      ContentMessage(message.senderAddress, message.content, message.needReply, rpcCallContext)
    }

    def onEndpointStopped(): Unit = {
      callback.onFailure(
        new SparkException(s"Could not find ${message.receiver.name} or it has been stopped"))
    }

    postMessageToInbox(message.receiver.name, createMessage, onEndpointStopped)
  }

  def postMessage(message: RequestMessage, p: Promise[Any]): Unit = {
    def createMessage(sender: NettyRpcEndpointRef): InboxMessage = {
      val rpcCallContext =
        new LocalNettyRpcCallContext(sender, message.senderAddress, message.needReply, p)
      ContentMessage(message.senderAddress, message.content, message.needReply, rpcCallContext)
    }

    def onEndpointStopped(): Unit = {
      p.tryFailure(
        new SparkException(s"Could not find ${message.receiver.name} or it has been stopped"))
    }

    postMessageToInbox(message.receiver.name, createMessage, onEndpointStopped)
  }

  private def postMessageToInbox(
      endpointName: String,
      createMessageFn: NettyRpcEndpointRef => InboxMessage,
      onStopped: () => Unit): Unit = {
    val shouldCallOnStop =
      synchronized {
        val data = endpoints.get(endpointName)
        if (stopped || data == null) {
          true
        } else {
          data.inbox.post(createMessageFn(data.ref))
          receivers.put(data)
          false
        }
      }
    if (shouldCallOnStop) {
      // We don't need to call `onStop` in the `synchronized` block
      onStopped()
    }
  }

  private val parallelism = nettyEnv.conf.getInt("spark.rpc.netty.dispatcher.parallelism",
    Runtime.getRuntime.availableProcessors())

  private val executor = ThreadUtils.newDaemonFixedThreadPool(parallelism, "dispatcher-event-loop")

  (0 until parallelism) foreach { _ =>
    executor.execute(new MessageLoop)
  }

  def stop(): Unit = {
    synchronized {
      if (stopped) {
        return
      }
      stopped = true
    }
    // Stop all endpoints. This will queue all endpoints for processing by the message loops.
    endpoints.keySet().asScala.foreach(unregisterRpcEndpoint)
    // Enqueue a message that tells the message loops to stop.
    receivers.put(PoisonEndpoint)
    executor.shutdown()
  }

  def awaitTermination(): Unit = {
    executor.awaitTermination(Long.MaxValue, TimeUnit.MILLISECONDS)
  }

  /**
   * Return if the endpoint exists
   */
  def verify(name: String): Boolean = {
    endpoints.containsKey(name)
  }

  private class MessageLoop extends Runnable {
    override def run(): Unit = {
      try {
        while (true) {
          try {
            val data = receivers.take()
            if (data == PoisonEndpoint) {
              // Put PoisonEndpoint back so that other MessageLoops can see it.
              receivers.put(PoisonEndpoint)
              return
            }
            data.inbox.process(Dispatcher.this)
          } catch {
            case NonFatal(e) => logError(e.getMessage, e)
          }
        }
      } catch {
        case ie: InterruptedException => // exit
      }
    }
  }

  /**
   * A poison endpoint that indicates MessageLoop should exit its loop.
   */
  private val PoisonEndpoint = new EndpointData(null, null, null)
}
