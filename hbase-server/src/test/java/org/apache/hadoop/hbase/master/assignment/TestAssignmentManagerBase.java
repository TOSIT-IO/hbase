/**
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
package org.apache.hadoop.hbase.master.assignment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CallQueueTooBigException;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.ipc.CallTimeoutException;
import org.apache.hadoop.hbase.ipc.ServerNotRunningYetException;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureConstants;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.hadoop.hbase.master.procedure.ProcedureSyncWait;
import org.apache.hadoop.hbase.master.procedure.RSProcedureDispatcher;
import org.apache.hadoop.hbase.procedure2.Procedure;
import org.apache.hadoop.hbase.procedure2.ProcedureMetrics;
import org.apache.hadoop.hbase.procedure2.store.wal.WALProcedureStore;
import org.apache.hadoop.hbase.regionserver.RegionServerAbortedException;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RegionServerStatusProtos;

/**
 * Base class for AM test.
 */
public class TestAssignmentManagerBase {
  private static final Logger LOG = LoggerFactory.getLogger(TestAssignmentManagerBase.class);

  @Rule
  public TestName name = new TestName();
  @Rule
  public final ExpectedException exception = ExpectedException.none();

  private static final int PROC_NTHREADS = 64;
  protected static final int NREGIONS = 1 * 1000;
  protected static final int NSERVERS = Math.max(1, NREGIONS / 100);

  protected HBaseTestingUtility UTIL;
  protected MockRSProcedureDispatcher rsDispatcher;
  protected MockMasterServices master;
  protected AssignmentManager am;
  protected NavigableMap<ServerName, SortedSet<byte[]>> regionsToRegionServers =
      new ConcurrentSkipListMap<>();
  // Simple executor to run some simple tasks.
  protected ScheduledExecutorService executor;

  protected ProcedureMetrics assignProcMetrics;
  protected ProcedureMetrics unassignProcMetrics;

  protected long assignSubmittedCount = 0;
  protected long assignFailedCount = 0;
  protected long unassignSubmittedCount = 0;
  protected long unassignFailedCount = 0;

  protected void setupConfiguration(Configuration conf) throws Exception {
    FSUtils.setRootDir(conf, UTIL.getDataTestDir());
    conf.setBoolean(WALProcedureStore.USE_HSYNC_CONF_KEY, false);
    conf.setInt(WALProcedureStore.SYNC_WAIT_MSEC_CONF_KEY, 10);
    conf.setInt(MasterProcedureConstants.MASTER_PROCEDURE_THREADS, PROC_NTHREADS);
    conf.setInt(RSProcedureDispatcher.RS_RPC_STARTUP_WAIT_TIME_CONF_KEY, 1000);
    conf.setInt(AssignmentManager.ASSIGN_MAX_ATTEMPTS, 100); // Have many so we succeed eventually.
  }

  @Before
  public void setUp() throws Exception {
    UTIL = new HBaseTestingUtility();
    this.executor = Executors.newSingleThreadScheduledExecutor();
    setupConfiguration(UTIL.getConfiguration());
    master = new MockMasterServices(UTIL.getConfiguration(), this.regionsToRegionServers);
    rsDispatcher = new MockRSProcedureDispatcher(master);
    master.start(NSERVERS, rsDispatcher);
    am = master.getAssignmentManager();
    assignProcMetrics = am.getAssignmentManagerMetrics().getAssignProcMetrics();
    unassignProcMetrics = am.getAssignmentManagerMetrics().getUnassignProcMetrics();
    setUpMeta();
  }

  private void setUpMeta() throws Exception {
    rsDispatcher.setMockRsExecutor(new GoodRsExecutor());
    am.assign(RegionInfoBuilder.FIRST_META_REGIONINFO);
    am.wakeMetaLoadedEvent();
  }

  @After
  public void tearDown() throws Exception {
    master.stop("tearDown");
    this.executor.shutdownNow();
  }

  protected Future<byte[]> submitProcedure(final Procedure<MasterProcedureEnv> proc) {
    return ProcedureSyncWait.submitProcedure(master.getMasterProcedureExecutor(), proc);
  }

  protected byte[] waitOnFuture(final Future<byte[]> future) throws Exception {
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      LOG.info("ExecutionException", e);
      Exception ee = (Exception) e.getCause();
      if (ee instanceof InterruptedIOException) {
        for (Procedure<?> p : this.master.getMasterProcedureExecutor().getProcedures()) {
          LOG.info(p.toStringDetails());
        }
      }
      throw (Exception) e.getCause();
    }
  }

  // ============================================================================================
  //  Helpers
  // ============================================================================================
  protected void bulkSubmit(final AssignProcedure[] procs) throws Exception {
    final Thread[] threads = new Thread[PROC_NTHREADS];
    for (int i = 0; i < threads.length; ++i) {
      final int threadId = i;
      threads[i] = new Thread() {
        @Override
        public void run() {
          TableName tableName = TableName.valueOf("table-" + threadId);
          int n = (procs.length / threads.length);
          int start = threadId * n;
          int stop = start + n;
          for (int j = start; j < stop; ++j) {
            procs[j] = createAndSubmitAssign(tableName, j);
          }
        }
      };
      threads[i].start();
    }
    for (int i = 0; i < threads.length; ++i) {
      threads[i].join();
    }
    for (int i = procs.length - 1; i >= 0 && procs[i] == null; --i) {
      procs[i] = createAndSubmitAssign(TableName.valueOf("table-sync"), i);
    }
  }

  private AssignProcedure createAndSubmitAssign(TableName tableName, int regionId) {
    RegionInfo hri = createRegionInfo(tableName, regionId);
    AssignProcedure proc = am.createAssignProcedure(hri);
    master.getMasterProcedureExecutor().submitProcedure(proc);
    return proc;
  }

  protected RegionInfo createRegionInfo(final TableName tableName, final long regionId) {
    return RegionInfoBuilder.newBuilder(tableName).setStartKey(Bytes.toBytes(regionId))
        .setEndKey(Bytes.toBytes(regionId + 1)).setSplit(false).setRegionId(0).build();
  }

  private void sendTransitionReport(final ServerName serverName,
      final org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.RegionInfo regionInfo,
      final RegionServerStatusProtos.RegionStateTransition.TransitionCode state)
      throws IOException {
    RegionServerStatusProtos.ReportRegionStateTransitionRequest.Builder req =
        RegionServerStatusProtos.ReportRegionStateTransitionRequest.newBuilder();
    req.setServer(ProtobufUtil.toServerName(serverName));
    req.addTransition(
        RegionServerStatusProtos.RegionStateTransition.newBuilder().addRegionInfo(regionInfo)
            .setTransitionCode(state).setOpenSeqNum(1).build());
    am.reportRegionStateTransition(req.build());
  }

  private void doCrash(final ServerName serverName) {
    this.am.submitServerCrash(serverName, false/*No WALs here*/);
  }

  private void doRestart(final ServerName serverName) {
    try {
      this.master.restartRegionServer(serverName);
    } catch (IOException e) {
      LOG.warn("Can not restart RS with new startcode");
    }
  }

  private class NoopRsExecutor implements MockRSExecutor {
    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest request) throws IOException {
      if (request.getOpenRegionCount() > 0) {
        for (AdminProtos.OpenRegionRequest req : request.getOpenRegionList()) {
          for (AdminProtos.OpenRegionRequest.RegionOpenInfo openReq : req.getOpenInfoList()) {
            execOpenRegion(server, openReq);
          }
        }
      }
      if (request.getCloseRegionCount() > 0) {
        for (AdminProtos.CloseRegionRequest req : request.getCloseRegionList()) {
          execCloseRegion(server, req.getRegion().getValue().toByteArray());
        }
      }
      return AdminProtos.ExecuteProceduresResponse.newBuilder().build();
    }

    protected AdminProtos.OpenRegionResponse.RegionOpeningState execOpenRegion(ServerName server,
        AdminProtos.OpenRegionRequest.RegionOpenInfo regionInfo) throws IOException {
      return null;
    }

    protected AdminProtos.CloseRegionResponse execCloseRegion(ServerName server, byte[] regionName)
        throws IOException {
      return null;
    }
  }

  protected class GoodRsExecutor extends NoopRsExecutor {
    @Override
    protected AdminProtos.OpenRegionResponse.RegionOpeningState execOpenRegion(ServerName server,
        AdminProtos.OpenRegionRequest.RegionOpenInfo openReq) throws IOException {
      sendTransitionReport(server, openReq.getRegion(),
          RegionServerStatusProtos.RegionStateTransition.TransitionCode.OPENED);
      // Concurrency?
      // Now update the state of our cluster in regionsToRegionServers.
      SortedSet<byte[]> regions = regionsToRegionServers.get(server);
      if (regions == null) {
        regions = new ConcurrentSkipListSet<byte[]>(Bytes.BYTES_COMPARATOR);
        regionsToRegionServers.put(server, regions);
      }
      RegionInfo hri = ProtobufUtil.toRegionInfo(openReq.getRegion());
      if (regions.contains(hri.getRegionName())) {
        throw new UnsupportedOperationException(hri.getRegionNameAsString());
      }
      regions.add(hri.getRegionName());
      return AdminProtos.OpenRegionResponse.RegionOpeningState.OPENED;
    }

    @Override
    protected AdminProtos.CloseRegionResponse execCloseRegion(ServerName server, byte[] regionName)
        throws IOException {
      RegionInfo hri = am.getRegionInfo(regionName);
      sendTransitionReport(server, ProtobufUtil.toRegionInfo(hri),
          RegionServerStatusProtos.RegionStateTransition.TransitionCode.CLOSED);
      return AdminProtos.CloseRegionResponse.newBuilder().setClosed(true).build();
    }
  }

  protected static class ServerNotYetRunningRsExecutor implements MockRSExecutor {
    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      throw new ServerNotRunningYetException("wait on server startup");
    }
  }

  protected static class FaultyRsExecutor implements MockRSExecutor {
    private final IOException exception;

    public FaultyRsExecutor(final IOException exception) {
      this.exception = exception;
    }

    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      throw exception;
    }
  }

  protected class SocketTimeoutRsExecutor extends GoodRsExecutor {
    private final int maxSocketTimeoutRetries;
    private final int maxServerRetries;

    private ServerName lastServer;
    private int sockTimeoutRetries;
    private int serverRetries;

    public SocketTimeoutRsExecutor(int maxSocketTimeoutRetries, int maxServerRetries) {
      this.maxServerRetries = maxServerRetries;
      this.maxSocketTimeoutRetries = maxSocketTimeoutRetries;
    }

    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      // SocketTimeoutException should be a temporary problem
      // unless the server will be declared dead.
      if (sockTimeoutRetries++ < maxSocketTimeoutRetries) {
        if (sockTimeoutRetries == 1) assertNotEquals(lastServer, server);
        lastServer = server;
        LOG.debug("Socket timeout for server=" + server + " retries=" + sockTimeoutRetries);
        throw new SocketTimeoutException("simulate socket timeout");
      } else if (serverRetries++ < maxServerRetries) {
        LOG.info("Mark server=" + server + " as dead. serverRetries=" + serverRetries);
        master.getServerManager().moveFromOnlineToDeadServers(server);
        sockTimeoutRetries = 0;
        throw new SocketTimeoutException("simulate socket timeout");
      } else {
        return super.sendRequest(server, req);
      }
    }
  }

  /**
   * Takes open request and then returns nothing so acts like a RS that went zombie.
   * No response (so proc is stuck/suspended on the Master and won't wake up.). We
   * then send in a crash for this server after a few seconds; crash is supposed to
   * take care of the suspended procedures.
   */
  protected class HangThenRSCrashExecutor extends GoodRsExecutor {
    private int invocations;

    @Override
    protected AdminProtos.OpenRegionResponse.RegionOpeningState execOpenRegion(
        final ServerName server, AdminProtos.OpenRegionRequest.RegionOpenInfo openReq)
        throws IOException {
      if (this.invocations++ > 0) {
        // Return w/o problem the second time through here.
        return super.execOpenRegion(server, openReq);
      }
      // The procedure on master will just hang forever because nothing comes back
      // from the RS in this case.
      LOG.info("Return null response from serverName=" + server + "; means STUCK...TODO timeout");
      executor.schedule(new Runnable() {
        @Override
        public void run() {
          LOG.info("Sending in CRASH of " + server);
          doCrash(server);
        }
      }, 1, TimeUnit.SECONDS);
      return null;
    }
  }

  /**
   * Takes open request and then returns nothing so acts like a RS that went zombie.
   * No response (so proc is stuck/suspended on the Master and won't wake up.).
   * Different with HangThenRSCrashExecutor,  HangThenRSCrashExecutor will create
   * ServerCrashProcedure to handle the server crash. However, this HangThenRSRestartExecutor
   * will restart RS directly, situation for RS crashed when SCP is not enabled.
   */
  protected class HangThenRSRestartExecutor extends GoodRsExecutor {
    private int invocations;

    @Override
    protected AdminProtos.OpenRegionResponse.RegionOpeningState execOpenRegion(
        final ServerName server, AdminProtos.OpenRegionRequest.RegionOpenInfo openReq)
        throws IOException {
      if (this.invocations++ > 0) {
        // Return w/o problem the second time through here.
        return super.execOpenRegion(server, openReq);
      }
      // The procedure on master will just hang forever because nothing comes back
      // from the RS in this case.
      LOG.info("Return null response from serverName=" + server + "; means STUCK...TODO timeout");
      executor.schedule(new Runnable() {
        @Override
        public void run() {
          LOG.info("Restarting RS of " + server);
          doRestart(server);
        }
      }, 1, TimeUnit.SECONDS);
      return null;
    }
  }

  protected class HangOnCloseThenRSCrashExecutor extends GoodRsExecutor {
    public static final int TYPES_OF_FAILURE = 6;
    private int invocations;

    @Override
    protected AdminProtos.CloseRegionResponse execCloseRegion(ServerName server, byte[] regionName)
        throws IOException {
      switch (this.invocations++) {
        case 0:
          throw new NotServingRegionException("Fake");
        case 1:
          executor.schedule(new Runnable() {
            @Override
            public void run() {
              LOG.info("Sending in CRASH of " + server);
              doCrash(server);
            }
          }, 1, TimeUnit.SECONDS);
          throw new RegionServerAbortedException("Fake!");
        case 2:
          executor.schedule(new Runnable() {
            @Override
            public void run() {
              LOG.info("Sending in CRASH of " + server);
              doCrash(server);
            }
          }, 1, TimeUnit.SECONDS);
          throw new RegionServerStoppedException("Fake!");
        case 3:
          throw new ServerNotRunningYetException("Fake!");
        case 4:
          LOG.info("Returned null from serverName={}; means STUCK...TODO timeout", server);
          executor.schedule(new Runnable() {
            @Override
            public void run() {
              LOG.info("Sending in CRASH of " + server);
              doCrash(server);
            }
          }, 1, TimeUnit.SECONDS);
          return null;
        default:
          return super.execCloseRegion(server, regionName);
      }
    }
  }

  protected class RandRsExecutor extends NoopRsExecutor {
    private final Random rand = new Random();

    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      switch (rand.nextInt(5)) {
        case 0:
          throw new ServerNotRunningYetException("wait on server startup");
        case 1:
          throw new SocketTimeoutException("simulate socket timeout");
        case 2:
          throw new RemoteException("java.io.IOException", "unexpected exception");
        default:
          // fall out
      }
      return super.sendRequest(server, req);
    }

    @Override
    protected AdminProtos.OpenRegionResponse.RegionOpeningState execOpenRegion(
        final ServerName server, AdminProtos.OpenRegionRequest.RegionOpenInfo openReq)
        throws IOException {
      switch (rand.nextInt(6)) {
        case 0:
          LOG.info("Return OPENED response");
          sendTransitionReport(server, openReq.getRegion(),
              RegionServerStatusProtos.RegionStateTransition.TransitionCode.OPENED);
          return AdminProtos.OpenRegionResponse.RegionOpeningState.OPENED;
        case 1:
          LOG.info("Return transition report that OPENED/ALREADY_OPENED response");
          sendTransitionReport(server, openReq.getRegion(),
              RegionServerStatusProtos.RegionStateTransition.TransitionCode.OPENED);
          return AdminProtos.OpenRegionResponse.RegionOpeningState.ALREADY_OPENED;
        case 2:
          LOG.info("Return transition report that FAILED_OPEN/FAILED_OPENING response");
          sendTransitionReport(server, openReq.getRegion(),
              RegionServerStatusProtos.RegionStateTransition.TransitionCode.FAILED_OPEN);
          return AdminProtos.OpenRegionResponse.RegionOpeningState.FAILED_OPENING;
        default:
          // fall out
      }
      // The procedure on master will just hang forever because nothing comes back
      // from the RS in this case.
      LOG.info(
          "Return null as response; means proc stuck so we send in a crash report after a few seconds...");
      executor.schedule(new Runnable() {
        @Override
        public void run() {
          LOG.info("Delayed CRASHING of " + server);
          doCrash(server);
        }
      }, 5, TimeUnit.SECONDS);
      return null;
    }

    @Override
    protected AdminProtos.CloseRegionResponse execCloseRegion(ServerName server, byte[] regionName)
        throws IOException {
      AdminProtos.CloseRegionResponse.Builder resp = AdminProtos.CloseRegionResponse.newBuilder();
      boolean closed = rand.nextBoolean();
      if (closed) {
        RegionInfo hri = am.getRegionInfo(regionName);
        sendTransitionReport(server, ProtobufUtil.toRegionInfo(hri),
            RegionServerStatusProtos.RegionStateTransition.TransitionCode.CLOSED);
      }
      resp.setClosed(closed);
      return resp.build();
    }
  }

  protected class CallQueueTooBigOnceRsExecutor extends GoodRsExecutor {

    private boolean invoked = false;

    private ServerName lastServer;

    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      if (!invoked) {
        lastServer = server;
        invoked = true;
        throw new CallQueueTooBigException("simulate queue full");
      }
      // better select another server since the server is over loaded, but anyway, it is fine to
      // still select the same server since it is not dead yet...
      if (lastServer.equals(server)) {
        LOG.warn("We still select the same server, which is not good.");
      }
      return super.sendRequest(server, req);
    }
  }

  protected class TimeoutThenCallQueueTooBigRsExecutor extends GoodRsExecutor {

    private final int queueFullTimes;

    private int retries;

    private ServerName lastServer;

    public TimeoutThenCallQueueTooBigRsExecutor(int queueFullTimes) {
      this.queueFullTimes = queueFullTimes;
    }

    @Override
    public AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException {
      retries++;
      if (retries == 1) {
        lastServer = server;
        throw new CallTimeoutException("simulate call timeout");
      }
      // should always retry on the same server
      assertEquals(lastServer, server);
      if (retries < queueFullTimes) {
        throw new CallQueueTooBigException("simulate queue full");
      }
      return super.sendRequest(server, req);
    }
  }

  protected interface MockRSExecutor {
    AdminProtos.ExecuteProceduresResponse sendRequest(ServerName server,
        AdminProtos.ExecuteProceduresRequest req) throws IOException;
  }

  protected class MockRSProcedureDispatcher extends RSProcedureDispatcher {
    private MockRSExecutor mockRsExec;

    public MockRSProcedureDispatcher(final MasterServices master) {
      super(master);
    }

    public void setMockRsExecutor(final MockRSExecutor mockRsExec) {
      this.mockRsExec = mockRsExec;
    }

    @Override
    protected void remoteDispatch(ServerName serverName, Set<RemoteProcedure> remoteProcedures) {
      submitTask(new MockRemoteCall(serverName, remoteProcedures));
    }

    private class MockRemoteCall extends ExecuteProceduresRemoteCall {
      public MockRemoteCall(final ServerName serverName, final Set<RemoteProcedure> operations) {
        super(serverName, operations);
      }

      @Override
      public void dispatchOpenRequests(MasterProcedureEnv env,
          List<RegionOpenOperation> operations) {
        request.addOpenRegion(buildOpenRegionRequest(env, getServerName(), operations));
      }

      @Override
      public void dispatchCloseRequests(MasterProcedureEnv env,
          List<RegionCloseOperation> operations) {
        for (RegionCloseOperation op : operations) {
          request.addCloseRegion(op.buildCloseRegionRequest(getServerName()));
        }
      }

      @Override
      protected AdminProtos.ExecuteProceduresResponse sendRequest(final ServerName serverName,
          final AdminProtos.ExecuteProceduresRequest request) throws IOException {
        return mockRsExec.sendRequest(serverName, request);
      }
    }
  }

  protected void collectAssignmentManagerMetrics() {
    assignSubmittedCount = assignProcMetrics.getSubmittedCounter().getCount();
    assignFailedCount = assignProcMetrics.getFailedCounter().getCount();
    unassignSubmittedCount = unassignProcMetrics.getSubmittedCounter().getCount();
    unassignFailedCount = unassignProcMetrics.getFailedCounter().getCount();
  }
}
