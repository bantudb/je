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

package com.sleepycat.je.rep.impl.networkRestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.CommitToken;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.impl.node.Feeder;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.stream.FeederReplicaSyncup;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.je.rep.vlsn.VLSNIndex;
import com.sleepycat.je.util.DbBackup;
import com.sleepycat.je.utilint.TestHookAdapter;
import com.sleepycat.je.utilint.VLSN;

/**
 * Tests various aspects of network replication stream behavior.
 */
public class RepStreamTest extends RepTestBase {

    final int heartBeatInterval =
        Integer.parseInt(RepParams.HEARTBEAT_INTERVAL.getDefault());

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Tests that barren files are deleted even when they are in the VLSN range.
     *
     * 1) Disable checkpoints and cleaning, and updates to group database.
     * 2) Populate the store with rep entries.
     * 3) Force start of a new log file
     * 4) Generate non-ha garbage so no new VLSNs are created, that is, create
     *    barren files.
     * 5) Force start of a new log file
     * 6) Add rep entries to the store to create a barren file sandwich.
     * 7) Add more non-ha garbage to trailing barren files as well
     * 8) Run cleaner
     * 9) Run checkpointer to force deletion of cleaned files
     * 10) Check that the barren files have been deleted.
     * 11) Start up another member of the group which will use the replication
     * stream (which spans the deleted barren files) to initialize itself to
     * verify that the feeder can deal with missing intermediate files.
     */
    @Test
    public void testBarrenFileDeletion() {

        setEnvConfigParam(EnvironmentParams.ENV_RUN_CLEANER, "false");
        setEnvConfigParam(EnvironmentParams.ENV_RUN_CHECKPOINTER, "false");
        setEnvConfigParam(EnvironmentParams.LOG_FILE_MAX, "20000");
        /*
         * To avoid creation of ha entries from updating the group database.
         */
        setRepConfigParam(RepParams.FEEDER_MANAGER_POLL_TIMEOUT, "10000 s");

        createGroup(1);

        final ReplicatedEnvironment env = repEnvInfo[0].getEnv();

        populateDB(env, "testDB", 1000);

        final RepImpl repImpl = repEnvInfo[0].getRepImpl();

        /* Start a new log file; it will be rendered barren . */
        final long firstBarrenFile = forceNewLogFile(env);

        genNonHAGarbage(env, repImpl, 1000);
        final long lastBarrenFile = forceNewLogFile(env) - 1;
        assertTrue(lastBarrenFile >= firstBarrenFile);

        populateDB(env, "testDB1", 1000);

        FileManager fileManager = repImpl.getFileManager();

        assertTrue(fileManager.getLastFileNum() > lastBarrenFile);

        final long firstTrailingBarrenFile = forceNewLogFile(env);
        genNonHAGarbage(env, repImpl, 1000);
        final long lastTrailingBarrenFile = forceNewLogFile(env) - 1;

        /* Write some more, so that we create additional files. */
        genNonHAGarbage(env, repImpl, 1000);

        /* Keep cleaning until it quiesces. */
        while (repImpl.invokeCleaner(true) > 0) {
        }

        final CheckpointConfig force = new CheckpointConfig().setForce(true);
        /* Cause the file deletion to happen. */
        env.checkpoint(force);

        /*
         * Verify that all (sandwich and trailing) barren files have
         * been deleted.
         */
        for (long i=firstBarrenFile; i <= lastBarrenFile; i++) {
            assertFalse(fileManager.isFileValid(i));
        }

        for (long i=firstTrailingBarrenFile; i <= lastTrailingBarrenFile; i++) {
            assertFalse(fileManager.isFileValid(i));
        }

        /*
         * Ensure that the node1 Feeder can read over the barren files that
         * were deleted earlier to initialize node2.
         */
        repEnvInfo[0].closeEnv();
        setRepConfigParam(RepParams.FEEDER_MANAGER_POLL_TIMEOUT,
                          RepParams.FEEDER_MANAGER_POLL_TIMEOUT.getDefault());
        repEnvInfo[0].openEnv();

        repEnvInfo[1].openEnv();
    }

    /**
     * Ensure that there is no ILE when a node that has been down for a time
     * period exceeding the replication stream timeout rejoins and the log
     * records it needs are still in the VLSN index even if the gcbvlsn has
     * moved on. This behavior relies on the retention of log files for
     * replication based on available storage and the negotiation of
     * VLSN matchpoints during syncup that are < GCBVLSN.
     *
     * 1) Create a 3 node (with rep stream timeout to a small interval (10 s)
     * group and populate it.
     *
     * 2) Shut down rn3
     *
     * 3) make further mods
     *
     * 4) Wait the timeout period to ensure that rn3 does not contribute to the
     * GCBVLSN
     *
     * 5) Open rn3. We should not encounter an ILE, even though the syncpoint
     * is < GCBVLSN.
     *
     * This test turns off the cleaner thread and is also careful to not close
     * and open the rn1 and rn2 since doing so would run the cleaner despite
     * the ENV_RUN_CLEANER setting.
     */
    @Test
    public void testUnnecessaryILE() throws InterruptedException {
        /*
         * Ensure that the cleaner does not remove log files thus provoking an
         * ILE, defeating the purpose of this test.
         */
        setEnvConfigParam(EnvironmentParams.ENV_RUN_CLEANER, "false");
        final long rn3maxVLSN = setupTimedOutEnvironment();
        repEnvInfo[0].getRepImpl().getRepNode().recalculateGlobalCBVLSN();
        VLSN gcbvlsn = repEnvInfo[0].getRepImpl().getRepNode().getGlobalCBVLSN();
        assertTrue(gcbvlsn.getSequence() > rn3maxVLSN);

        try {
            repEnvInfo[2].openEnv();
        } catch (InsufficientLogException ile) {
            fail("Unexpected ILE sr22782");
        }
    }

    /**
     * Test that the Feeder prevents cleaned log files from being deleted if
     * they contain the starting VLSN selected during a syncup. Tests fix for
     * [#24299]
     * "Failing to protect log files from deletion between syncup and replay".
     */
    @Test
    public void testSyncupCleanerInteraction() throws InterruptedException {
        /* Take explicit control of the cleaner. */
        setEnvConfigParam(EnvironmentParams.ENV_RUN_CLEANER, "false");
        final long rn3maxVLSN = setupTimedOutEnvironment();
        repEnvInfo[0].getRepImpl().getRepNode().recalculateGlobalCBVLSN();
        VLSN gcbvlsn = repEnvInfo[0].getRepImpl().getRepNode().getGlobalCBVLSN();
        assertTrue(gcbvlsn.getSequence() > rn3maxVLSN);

        final RepEnvInfo minfo = findMaster(repEnvInfo);

        final AtomicBoolean hookWasRun = new AtomicBoolean(false);
        final TestHookAdapter<Feeder> hook =
            new TestHookAdapter<Feeder>() {
                @Override
                public void doHook(Feeder feeder) {
                    final RepNode rn = feeder.getRepNode();
                    if (!rn.getNameIdPair().getName().
                        equals(minfo.getRepConfig().getNodeName())) {
                        return;
                    }
                    hookWasRun.set(true);
                    /* Run the cleaner. */
                    while (rn.getRepImpl().invokeCleaner(true) > 0) {
                        /* Keep cleaning until it quiesces. */
                    }
                    final CheckpointConfig force =
                        new CheckpointConfig().setForce(true);
                    rn.getRepImpl().invokeCheckpoint(force, "unit test");

                }
            };
        try {
            FeederReplicaSyncup.setAfterSyncupEndedHook(hook);
            repEnvInfo[2].openEnv();
            assertTrue(hookWasRun.get());
        } catch (InsufficientLogException ile) {
            fail("Unexpected ILE sr22782");
        }
    }

    /**
     * Utility method to force creation of a new log file on the next write.
     *
     * @return the last file number in the log sequence
     */
    private static long forceNewLogFile(Environment env) {
        final EnvironmentImpl envImpl = DbInternal.getNonNullEnvImpl(env);
        DbBackup dbBackup = new DbBackup(env);
        dbBackup.startBackup();
        dbBackup.endBackup();
        return envImpl.getFileManager().getLastFileNum();
    }

    /**
     * Run steps 1-5 described above.
     * @return The last VLSN at the timed out environment
     */
    private long setupTimedOutEnvironment()
        throws InterruptedException {

        /*
         * Shorten the rep stream timeout to eliminate rn3 from the global
         * cbvlsn computation.
         */
        final int streamTimeoutMs = 10000;
        setRepConfigParam(RepParams.REP_STREAM_TIMEOUT,
                          streamTimeoutMs + " ms");

        long startMs = System.currentTimeMillis();
        createGroup(3);
        ReplicatedEnvironment master = repEnvInfo[0].getEnv();
        assertTrue(master.getState().isMaster());

        CommitToken ct = populateDB(repEnvInfo[0].getEnv(), "testDB", 10000);
        RepTestUtils.syncGroup(repEnvInfo);
        repEnvInfo[2].closeEnv();
        long elapsedMs = (System.currentTimeMillis() - startMs);
        /*
         * Verify that we have not exceeded the stream time out and advanced the
         * gcbvlsn already. Typically elapsedMs < 10 ms.
         */
        assertTrue("Machine too slow, elapsed time:" + elapsedMs,
                   elapsedMs < streamTimeoutMs);
        /* Expire the cbvlsn entry for rn3 */
        Thread.sleep(streamTimeoutMs + 10);

        /*
         * 1000 additional changes that rn3 will need to sync up when it joins
         * the group
         */
        populateDB(repEnvInfo[0].getEnv(), "testDB", 10000);
        /* Create delete fodder. */
        populateDB(repEnvInfo[0].getEnv(), "testDB", 10000);
        RepTestUtils.syncGroup(repEnvInfo);
        /* Allow for heartbeat exchange so that the gcbvlsn is current. */

        Thread.sleep(heartBeatInterval * 3); /* Allow for heartbeats. */

        return ct.getVLSN();
    }

    /**
     * Generate nRecords of non-ha garbage. It does so by repeatedly
     * overwriting the same key value pair in a non-replicated database and
     * deleting the key before exiting.
     */
    private void genNonHAGarbage(ReplicatedEnvironment rep,
                                 RepImpl repImpl,
                                 int nRecords)
        throws DatabaseException {

        Environment env = rep;
        final VLSNIndex vlsnIndex = repImpl.getVLSNIndex();
        final VLSN preVLSN = vlsnIndex.getRange().getLast();

        Database db = null;
        boolean done = false;
        DatabaseConfig nonRepDbConfig = new DatabaseConfig();
        nonRepDbConfig.setAllowCreate(true);
        nonRepDbConfig.setTransactional(true);
        nonRepDbConfig.setSortedDuplicates(false);
        nonRepDbConfig.setReplicated(false);
        TransactionConfig txnConfig =
            new TransactionConfig().setLocalWrite(true);
        Transaction txn = env.beginTransaction(null, txnConfig);
        String dbName = "garbage";
        String keyName = "garbage";
        StringBinding.stringToEntry(keyName, key);

        try {
            db = env.openDatabase(txn, dbName, nonRepDbConfig);
            txn.commit();
            txn = null;
            txn = env.beginTransaction(null, txnConfig);
            for (int i = 0; i < nRecords; i++) {
                /* Overwrite. */
                db.put(txn, key, key);
            }
            db.delete(txn, key);
            txn.commit();
            done = true;
        } finally {
            if (txn != null && !done) {
                txn.abort();
            }
            if (db != null) {
                db.close();
            }
        }

        final VLSN postVLSN = vlsnIndex.getRange().getLast();
        assertEquals(preVLSN, postVLSN);
    }
}
