/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperListener;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

/**
 * Handles everything on master-side related to master election.
 *
 * <p>Listens and responds to ZooKeeper notifications on the master znode,
 * both <code>nodeCreated</code> and <code>nodeDeleted</code>.
 *
 * <p>Contains blocking methods which will hold up backup masters, waiting
 * for the active master to fail.
 *
 * <p>This class is instantiated in the HMaster constructor and the method
 * #blockUntilBecomingActiveMaster() is called to wait until becoming
 * the active master of the cluster.
 */
class ActiveMasterManager extends ZooKeeperListener {
  private static final Log LOG = LogFactory.getLog(ActiveMasterManager.class);

  final AtomicBoolean clusterHasActiveMaster = new AtomicBoolean(false);

  private final ServerName sn;
  private final Server master;

  /**
   * @param watcher
   * @param sn ServerName
   * @param master In an instance of a Master.
   */
  ActiveMasterManager(ZooKeeperWatcher watcher, ServerName sn, Server master) {
    super(watcher);
    this.sn = sn;
    this.master = master;
  }

  @Override
  public void nodeCreated(String path) {
    if(path.equals(watcher.masterAddressZNode) && !master.isStopped()) {
      handleMasterNodeChange();
    }
  }

  @Override
  public void nodeDeleted(String path) {
    if(path.equals(watcher.masterAddressZNode) && !master.isStopped()) {
      handleMasterNodeChange();
    }
  }

  /**
   * Handle a change in the master node.  Doesn't matter whether this was called
   * from a nodeCreated or nodeDeleted event because there are no guarantees
   * that the current state of the master node matches the event at the time of
   * our next ZK request.
   *
   * <p>Uses the watchAndCheckExists method which watches the master address node
   * regardless of whether it exists or not.  If it does exist (there is an
   * active master), it returns true.  Otherwise it returns false.
   *
   * <p>A watcher is set which guarantees that this method will get called again if
   * there is another change in the master node.
   */
  private void handleMasterNodeChange() {
    // Watch the node and check if it exists.
    try {
      synchronized(clusterHasActiveMaster) {
        if(ZKUtil.watchAndCheckExists(watcher, watcher.masterAddressZNode)) {
          // A master node exists, there is an active master
          LOG.debug("A master is now available");
          clusterHasActiveMaster.set(true);
        } else {
          // Node is no longer there, cluster does not have an active master
          LOG.debug("No master available. Notifying waiting threads");
          clusterHasActiveMaster.set(false);
          // Notify any thread waiting to become the active master
          clusterHasActiveMaster.notifyAll();
        }
      }
    } catch (KeeperException ke) {
      master.abort("Received an unexpected KeeperException, aborting", ke);
    }
  }

  /**
   * Block until becoming the active master.
   *
   * Method blocks until there is not another active master and our attempt
   * to become the new active master is successful.
   *
   * This also makes sure that we are watching the master znode so will be
   * notified if another master dies.
   * @param startupStatus 
   * @return True if no issue becoming active master else false if another
   * master was running or if some other problem (zookeeper, stop flag has been
   * set on this Master)
   */
  boolean blockUntilBecomingActiveMaster(MonitoredTask startupStatus) {
    startupStatus.setStatus("Trying to register in ZK as active master");
    boolean cleanSetOfActiveMaster = true;
    // Try to become the active master, watch if there is another master.
    // Write out our ServerName as versioned bytes.
    try {
      if (ZKUtil.createEphemeralNodeAndWatch(this.watcher,
          this.watcher.masterAddressZNode, sn.getVersionedBytes())) {
        // We are the master, return
        startupStatus.setStatus("Successfully registered as active master.");
        this.clusterHasActiveMaster.set(true);
        LOG.info("Master=" + this.sn);
        return cleanSetOfActiveMaster;
      }
      cleanSetOfActiveMaster = false;

      // There is another active master running elsewhere or this is a restart
      // and the master ephemeral node has not expired yet.
      this.clusterHasActiveMaster.set(true);
      byte [] bytes =
        ZKUtil.getDataAndWatch(this.watcher, this.watcher.masterAddressZNode);
      ServerName currentMaster = ServerName.parseVersionedServerName(bytes);
      if (ServerName.isSameHostnameAndPort(currentMaster, this.sn)) {
        String msg = ("Current master has this master's address, " + currentMaster +
          "; master was restarted?  Waiting on znode to expire...");
        LOG.info(msg);
        startupStatus.setStatus(msg);
        // Hurry along the expiration of the znode.
        ZKUtil.deleteNode(this.watcher, this.watcher.masterAddressZNode);
      } else {
        String msg = "Another master is the active master, " + currentMaster +
        "; waiting to become the next active master";
        LOG.info(msg);
        startupStatus.setStatus(msg);
      }
    } catch (KeeperException ke) {
      master.abort("Received an unexpected KeeperException, aborting", ke);
      return false;
    }
    synchronized (this.clusterHasActiveMaster) {
      while (this.clusterHasActiveMaster.get() && !this.master.isStopped()) {
        try {
          this.clusterHasActiveMaster.wait();
        } catch (InterruptedException e) {
          // We expect to be interrupted when a master dies, will fall out if so
          LOG.debug("Interrupted waiting for master to die", e);
        }
      }
      if (this.master.isStopped()) {
        return cleanSetOfActiveMaster;
      }
      // Try to become active master again now that there is no active master
      blockUntilBecomingActiveMaster(startupStatus);
    }
    return cleanSetOfActiveMaster;
  }

  /**
   * @return True if cluster has an active master.
   */
  public boolean isActiveMaster() {
    try {
      if (ZKUtil.checkExists(watcher, watcher.masterAddressZNode) >= 0) {
        return true;
      }
    } 
    catch (KeeperException ke) {
      LOG.info("Received an unexpected KeeperException when checking " +
          "isActiveMaster : "+ ke);
    }
    return false;
  }

  public void stop() {
    try {
      // If our address is in ZK, delete it on our way out
      byte [] bytes =
        ZKUtil.getDataAndWatch(watcher, watcher.masterAddressZNode);
      // TODO: redo this to make it atomic (only added for tests)
      ServerName master = ServerName.parseVersionedServerName(bytes);
      if (master != null &&  master.equals(this.sn)) {
        ZKUtil.deleteNode(watcher, watcher.masterAddressZNode);
      }
    } catch (KeeperException e) {
      LOG.error(this.watcher.prefix("Error deleting our own master address node"), e);
    }
  }
}
