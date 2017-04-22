/*-
 * Copyright (C) 2002, 2017, Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle Berkeley
 * DB Java Edition made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/berkeleydb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle Berkeley DB Java Edition for a copy of the
 * license and additional information.
 */
package com.sleepycat.je.rep.impl.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Durability.ReplicaAckPolicy;
import com.sleepycat.je.Durability.SyncPolicy;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.vlsn.VLSNIndex;
import com.sleepycat.je.rep.vlsn.VLSNRange;
import com.sleepycat.je.utilint.DummyFileStoreInfo;
import com.sleepycat.je.utilint.FileStoreInfo;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.VLSN;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Test that deletion of log files by the cleaner is throttled by the CBVLSN.
 */
public class CleanerThrottleTest extends TestBase {

    private static final int heartbeatMS = 500;

    /* Replication tests use multiple environments. */
    private final File envRoot = SharedTestUtils.getTestDir();
    private final Logger logger =
        LoggerUtils.getLoggerFixedPrefix(getClass(), "Test");
    private final StatsConfig statsConfig =
        new StatsConfig().setClear(true);

    private EnvironmentConfig envConfig;
    private ReplicationConfig repConfig;
    private RepEnvInfo[] repEnvInfo;
    private boolean killOneNodePinsVLSN = true;
    @Rule
    public TestName testName= new TestName();

    @Override
    @Before
    public void setUp()
        throws Exception {

        super.setUp();

        /*
         * Create default environment and replication configurations, which can
         * be modified in each test before creating the environment
         */
        envConfig = new EnvironmentConfig();
        DbInternal.disableParameterValidation(envConfig);

        /* Use uniformly small log files, to permit cleaning.  */
        envConfig.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "2000");
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);

        /* Turn off the cleaner so we can call cleanLog explicitly. */
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
        envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER,
                                 "false");

        repConfig = new ReplicationConfig();
        RepInternal.disableParameterValidation(repConfig);
        repConfig.setConfigParam(RepParams.HEARTBEAT_INTERVAL.getName(),
                                 String.valueOf(heartbeatMS));

        repEnvInfo = null;
        FileStoreInfo.setFactory(DummyFileStoreInfo.INSTANCE);
    }

    @Override
    @After
    public void tearDown()
        throws Exception {

        super.tearDown();
        FileStoreInfo.setFactory(null);
        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }

    /**
     * Test with all nodes running, using the Global CBVLSN and ignoring replay
     * cost.
     */
    @Test
    public void testAllNodesCleanGlobalCBVLSN()
        throws Exception {

        /* Disable protecting files based on replay cost */
        repConfig.setConfigParam(ReplicationConfig.REPLAY_COST_PERCENT, "0");

        runAndClean(false); // killOneNode
    }

    /**
     * Same, with replicas that are secondary nodes.
     */
    @Test
    public void testAllNodesCleanGlobalCBVLSNSecondary()
        throws Exception {

        /*
         * Disable protecting files based on replay cost by saying there is no
         * free disk space.
         */
        FileStoreInfo.setFactory(
            new DummyFileStoreInfo() {
                @Override
                public long getUsableSpace() {
                    return 0;
                }
            });

        /* Make replicas secondary nodes */
        repEnvInfo = RepTestUtils.setupEnvInfos(
            envRoot, 3, envConfig, repConfig);
        repEnvInfo[1].getRepConfig().setNodeType(NodeType.SECONDARY);
        repEnvInfo[2].getRepConfig().setNodeType(NodeType.SECONDARY);

        runAndClean(false); // killOneNode
    }

    /**
     * Test with all nodes running, using replay cost and not the Global
     * CBVLSN.
     */
    @Test
    public void testAllNodesCleanReplayCost()
        throws Exception {

        /* Disable Global CBVLSN */
        repConfig.setConfigParam(ReplicationConfig.REP_STREAM_TIMEOUT, "0");

        /*
         * Also try using a FileStoreInfo that says it is not supported --
         * should have no effect on results
         */
        FileStoreInfo.setFactory(
            new DummyFileStoreInfo() {
                @Override
                public void factoryCheckSupported() {
                    throw new UnsupportedOperationException(
                        "Test injected unsupported");
                }
            });

        runAndClean(false); // killOneNode
    }

    /**
     * Same, with replicas that are secondary nodes.
     */
    @Test
    public void testAllNodesCleanReplayCostSecondary()
        throws Exception {

        repConfig.setConfigParam(ReplicationConfig.REP_STREAM_TIMEOUT, "0");
        repEnvInfo = RepTestUtils.setupEnvInfos(
            envRoot, 3, envConfig, repConfig);
        repEnvInfo[1].getRepConfig().setNodeType(NodeType.SECONDARY);
        repEnvInfo[2].getRepConfig().setNodeType(NodeType.SECONDARY);

        /*
         * Also try using a FileStoreInfo that gets an IOException for every
         * other file -- should have no effect on the results
         */
        FileStoreInfo.setFactory(
            new DummyFileStoreInfo() {
                int count;
                @Override
                public FileStoreInfo factoryGetInfo(final String file)
                    throws IOException {

                    if ((count++ % 2) == 0) {
                        throw new IOException(
                            "Test injected failure in getInfo");
                    }
                    return super.getInfo(file);
                }
            });

        runAndClean(false); // killOneNode
    }

    /**
     * Test with a different replay cost percentage.
     */
    @Test
    public void testAllNodesCleanReplayCostDifferentPercentage()
        throws Exception {

        /* Disable Global CBVLSN */
        repConfig.setConfigParam(ReplicationConfig.REP_STREAM_TIMEOUT, "0");

        /*
         * Increase the expected number of protected files because the replay
         * cost should protect even more files if we reduce the replay cost
         * percentage
         */
        repConfig.setConfigParam(ReplicationConfig.REPLAY_COST_PERCENT, "100");

        runAndClean(false); // killOneNode
    }

    /**
     * Test with all nodes running, using replay cost, the Global CBVLSN, and
     * confirm that available free disk space controls when files are deleted.
     */
    @Test
    public void testAllNodesCleanInsufficientFreeSpace()
        throws Exception {

        /* Disable Global CBVLSN */
        repConfig.setConfigParam(ReplicationConfig.REP_STREAM_TIMEOUT, "0");

        runAndClean(false); // killOneNode

        /*
         * Then modify the FileStoreInfo to say that there is only 5% free
         * space, with 10% required to allow retaining excess log files.
         */
        FileStoreInfo.setFactory(
            new DummyFileStoreInfo() {
                final long totalDiskSpace = 1000000000;
                @Override
                public long getTotalSpace() {
                    return totalDiskSpace;
                }
                @Override
                public long getUsableSpace() {
                    return (long) (totalDiskSpace * .05);
                }
            });

        cleanAndCheck(false);
    }

    /**
     * One node from a three node group is killed off, should hold the
     * replication stream steady by the Global CBVLSN, so long as
     * REP_STREAM_TIMEOUT is long enough.
     */
    @Test
    public void testTwoNodesClean()
        throws Exception {

        /*
         * Also try using a FileStoreInfo that gets an IOException when asked
         * the amount of usable space
         */
        FileStoreInfo.setFactory(
            new DummyFileStoreInfo() {
                @Override
                public long getUsableSpace()
                    throws IOException {

                    throw new IOException(
                        "Test injected failure in getUsableSpace");
                }
            });

        runAndClean(true); // killOneNode
    }

    /**
     * Same, with replicas that are secondary nodes.
     */
    @Test
    public void testTwoNodesCleanSecondary()
        throws Exception {

        repEnvInfo = RepTestUtils.setupEnvInfos(
            envRoot, 3, envConfig, repConfig);
        repEnvInfo[1].getRepConfig().setNodeType(NodeType.SECONDARY);
        repEnvInfo[2].getRepConfig().setNodeType(NodeType.SECONDARY);

        /*
         * Don't expect the first VLSN to be held low by the killed node; it
         * should increase just as if no nodes were killed because the
         * secondary node doesn't participate in the global CBVLSN.
         */
        killOneNodePinsVLSN = false;

        runAndClean(true); // killOneNode
    }

    /**
     * Same with the global CBVLSN disabled.
     */
    @Test
    public void testTwoNodesCleanNoGlobalCBVLSN()
        throws Exception {

        repConfig.setConfigParam(ReplicationConfig.REP_STREAM_TIMEOUT, "0");

        /*
         * Disabling the global CBVLSN means that the killed node will not pin
         * the CBVLSN.
         */
        killOneNodePinsVLSN = false;

        runAndClean(true); // killOneNode
    }

    /**
     * Create 3 nodes and replicate operations.  If killOneNode is true, kill
     * one of the replicas, which we will expect to prevent the global CBVLSN
     * from advancing unless killOneNodePinsVLSN is false.
     */
    public void runAndClean(boolean killOneNode)
        throws Exception {

        try {

            /* Create a 3 node group by default */
            if (repEnvInfo == null) {
                repEnvInfo = RepTestUtils.setupEnvInfos(
                    envRoot, 3, envConfig, repConfig);
            }
            ReplicatedEnvironment master = RepTestUtils.joinGroup(repEnvInfo);
            VLSNIndex vlsnIndex =
                RepInternal.getNonNullRepImpl(master).getVLSNIndex();
            VLSN initialLastVLSN = vlsnIndex.getRange().getLast();
            logger.info("Initial last VLSN: " + initialLastVLSN);

            if (killOneNode) {
                for (RepEnvInfo repi : repEnvInfo) {
                    if (repi.getEnv() != master) {
                        /*
                         * Wait for the heartbeat to make it back and update
                         * the CBVLSN associated with the node to a non-null
                         * value
                         */
                        Thread.sleep(heartbeatMS * 3);
                        repi.closeEnv();
                        break;
                    }
                }
            }

            /* Run a workload that will create cleanable waste. */
            doWastefulWork(master);

            /*
             * Check how the replication stream has grown, and what the last
             * VLSN is now.
             */
            VLSN lastVLSN = vlsnIndex.getRange().getLast();
            RepTestUtils.syncGroupToVLSN(repEnvInfo,
                                         (killOneNode ?
                                          repEnvInfo.length-1 :
                                          repEnvInfo.length),
                                         lastVLSN);
            Thread.sleep(heartbeatMS * 3);
            lastVLSN = vlsnIndex.getRange().getLast();

            cleanAndCheck(killOneNode);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void doWastefulWork(ReplicatedEnvironment master) {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        Database db = master.openDatabase(null, "test", dbConfig);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry(new byte[100]);

        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setDurability
            (new Durability(SyncPolicy.NO_SYNC,
                            SyncPolicy.NO_SYNC,
                            ReplicaAckPolicy.SIMPLE_MAJORITY));
        try {
            for (int i = 0; i < 40; i++) {
                IntegerBinding.intToEntry(i, key);
                Transaction txn = master.beginTransaction(null, txnConfig);
                for (int repeat = 0; repeat < 30; repeat++) {
                    db.put(txn, key, data);
                }
                db.delete(txn, key);
                txn.commit();
            }

            /* One more synchronous one to flush the log files. */
            IntegerBinding.intToEntry(101, key);
            txnConfig.setDurability
                (new Durability(SyncPolicy.SYNC,
                                SyncPolicy.SYNC,
                                ReplicaAckPolicy.SIMPLE_MAJORITY));
            Transaction txn = master.beginTransaction(null, txnConfig);
            db.put(txn, key, data);
            db.delete(txn, key);
            txn.commit();
        } finally {
            db.close();
        }
    }


    private void cleanAndCheck(boolean killOneNode)
        throws InterruptedException {

        /* Run cleaning on each node. */
        for (RepEnvInfo repi : repEnvInfo) {
            cleanLog(repi.getEnv());
        }

        /* Allow time for heartbeat exchanges. */
        Thread.sleep(heartbeatMS * 3);
        /* Check VLSN index */
        for (RepEnvInfo repi : repEnvInfo) {
            if (repi.getEnv() == null) {
                if (killOneNode) {
                    continue;
                }
                fail("Environment is closed: " + repi);
            }
            VLSNIndex vlsnIndex =
                RepInternal.getNonNullRepImpl(repi.getEnv()).getVLSNIndex();
            VLSNRange range = vlsnIndex.getRange();
            logger.info(repi.getEnv().getNodeName() + ": " + range);
            //assertTrue(lastVLSN.compareTo(range.getFirst()) >= 0);
            if (killOneNode && killOneNodePinsVLSN) {
                assertEquals("First VLSN", 1, range.getFirst().getSequence());
            } else {
                /*
                 * Most of the replication stream should have been
                 * cleaned away. There should not be any more than 20
                 * VLSNs left.
                 */
                assertTrue("First VLSN should be greater than 900, was " +
                           range.getFirst(),
                           range.getFirst().getSequence() > 900);
            }
            EnvironmentStats stats = repi.getEnv().getStats(statsConfig);
            assertEquals("cleanerBacklog ", 0, stats.getCleanerBacklog());

            final int fileDeletionBacklog = stats.getFileDeletionBacklog();
            assertTrue(fileDeletionBacklog > 0);
            logger.info(testName.getMethodName() +
                        ": fileDeletionBacklog=" + fileDeletionBacklog);
        }
    }

    private void cleanLog(ReplicatedEnvironment repEnv) {
        if (repEnv == null) {
            return;
        }

        CheckpointConfig force = new CheckpointConfig();
        force.setForce(true);

        EnvironmentStats stats = repEnv.getStats(new StatsConfig());
        int numCleaned = 0;
        int cleanedThisRun = 0;
        long beforeNFileDeletes = stats.getNCleanerDeletions();
        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        logger.info("numCleanedFiles=" + numCleaned);

        stats = repEnv.getStats(new StatsConfig());
        long afterNFileDeletes = stats.getNCleanerDeletions();
        long actualDeleted = afterNFileDeletes - beforeNFileDeletes;

        logger.info(repEnv.getNodeName() +
                    " cbvlsn=" +
                    RepInternal.getNonNullRepImpl(repEnv).
                    getRepNode().getGroupCBVLSN() +
                    " deletedFiles=" + actualDeleted +
                    " numCleaned=" + numCleaned);
    }
}
