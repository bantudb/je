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


package com.sleepycat.je.rep.subscription;

import static com.sleepycat.je.rep.ReplicatedEnvironment.State.MASTER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Durability;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.NodeType;
import com.sleepycat.je.rep.RepInternal;
import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.rep.ReplicationGroup;
import com.sleepycat.je.rep.ReplicationNode;
import com.sleepycat.je.rep.impl.RepTestBase;
import com.sleepycat.je.rep.monitor.Monitor;
import com.sleepycat.je.rep.monitor.MonitorConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.vlsn.VLSNIndex;
import com.sleepycat.je.rep.vlsn.VLSNRange;
import com.sleepycat.je.tree.Key;
import com.sleepycat.je.utilint.InternalException;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.PollCondition;
import com.sleepycat.je.utilint.TestHookAdapter;
import com.sleepycat.je.utilint.VLSN;

/**
 * test subscription API to receive a replication stream
 * from a start VLSN
 */
public class SubscriptionTest extends RepTestBase {

    /* polling interval and timeout to check if test is done */
    private final static long TEST_POLL_INTERVAL_MS = 1000;
    private final static long TEST_POLL_TIMEOUT_MS = 120000;

    /* test db */
    private final String dbName = "SUBSCRIPTION_UNIT_TEST_DB";
    private final int startKey = 1;
    /* test db with 10k keys */
    private int numKeys = 1024*10;
    private List<Integer> keys;

    /* a rep group with 1 master, 2 replicas and 1 monitor */
    private int numReplicas = 2;
    private boolean hasMonitor = true;
    protected int numDataNodes = 1 + numReplicas;

    private Subscription subscription;
    private Monitor monitor;

    private Logger logger;
    @Override
    @Before
    public void setUp() throws Exception {
        groupSize = 1 + numReplicas + (hasMonitor ? 1 : 0);
        super.setUp();
        keys = new ArrayList<>();
        logger = LoggerUtils.getLoggerFixedPrefix(getClass(),
                "SubscriptionTest");
        logger.setLevel(Level.FINE);

        /* to be created in each test */
        subscription = null;
        monitor = null;
    }

    @Override
    @After
    public void tearDown()
        throws Exception {
        if (subscription != null) {
            subscription.shutdown();
        }
        subscription = null;

        if (monitor != null) {
            monitor.shutdown();
        }
        super.tearDown();
    }

    /**
     * Testcase of subscribing from the very beginning of VLSN. Assume the
     * VLSN index should have all entries available given the small set of
     * test data. This is the test case of basic subscription api usage.
     *
     * @throws Exception
     */
    @Test
    public void testSubscriptionBasic() throws Exception {

        /* a slightly bigger db */
        numKeys =  1024*100;

        testGroupUUIDhelper(false);
    }

    /**
     * Similar testcase to testSubscriptionBasic except that a rep group
     * uuid is tested in configuration. This is to test that subscription can
     * only succeed if the specified group uuid matches that of feeder.
     *
     * @throws Exception
     */
    @Test
    public void testSubscriptionGroupUUID() throws Exception {

        /* a slightly bigger db */
        numKeys =  1024*100;

        /* same test as above test but use a matching group uuid */
        testGroupUUIDhelper(true);

        /* now test a invalid random group uuid, subscription should fail */
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        SubscriptionConfig config = createSubConfig(masterEnv, true);
        config.setGroupUUID(UUID.randomUUID());
        Subscription failSubscription = new Subscription(config, logger);
         /* start streaming from the very beginning, should succeed */
        try {
            failSubscription.start();
            fail("Did not see exception due to mismatch group uuid");
        } catch (InternalException e) {
            logger.info("Expected exception due to mismatch group uuid: " +
                        e.getMessage());
        }

        failSubscription.shutdown();
    }

    /**
     * Test that the dummy env created by subscription is a secondary node
     *
     * @throws Exception
     */
    @Test
    public void testDummyEnvNodeType() throws Exception {

        /* a slightly bigger db */
        numKeys =  100;
         /* turn off data cleaner on master */
        turnOffMasterCleaner();

        /* create and verify a replication group */
        prepareTestEnv();

        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

         /* create a subscription */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeys);
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);
    
        final ReplicatedEnvironment dummyEnv = subscription.getDummyRepEnv();
        assert(!dummyEnv.getRepConfig().getNodeType().isElectable());
        assert(dummyEnv.getRepConfig().getNodeType().isSecondary());
    }
    
    /**
     * Testcase of subscribing from a particular VLSN. Assume the
     * VLSN index should have all entries available given the small set of
     * test data
     *
     * @throws Exception
     */
    @Test
    public void testSubscriptionFromVLSN() throws Exception {

        /* a small db is enough for this test */
        numKeys = 1024;

        /* turn off data cleaner on master */
        turnOffMasterCleaner();

        /* the start VLSN to subscribe*/
        final VLSN startVLSN = new VLSN(100);
        /* number of transactions we subscribe */
        final int numKeysToStream = 10;

        /* create and verify a replication group */
        prepareTestEnv();

        /* populate some data and verify */
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

        /* create a subscription */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeysToStream);
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);

        logger.info("subscription created at home " +
                    config.getSubscriberHome() +
                    ", start streaming " + numKeys + " items from feeder");


        subscription.start(startVLSN);

        /* let subscription run to receive expected # of keys */
        try {
            waitForTestDone(testCbk);
        } catch (TimeoutException te) {
            failTimeout();
        }

        /* verify */
        assertEquals("Mismatch start VLSN!", startVLSN,
                     testCbk.getFirstVLSN());
        assertEquals("Mismatch start VLSN from statistics",
                     subscription.getStatistics().getStartVLSN(),
                     testCbk.getFirstVLSN());

        subscription.shutdown();
    }

    /**
     * Testcase of subscribing from a NULL VLSN.
     *
     * @throws Exception
     */
    @Test
    public void testInvalidStartVLSN() throws Exception {

        numKeys = 200;

        /* turn off data cleaner on master */
        turnOffMasterCleaner();

        /* the start VLSN to subscribe*/
        final VLSN startVLSN = VLSN.NULL_VLSN;
        /* number of transactions we subscribe */
        final int numKeysToStream = 10;

        /* create and verify a replication group */
        prepareTestEnv();

        /* populate some data and verify */
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

        /* create a subscription */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeysToStream);
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);

        logger.info("subscription created at home " +
                    config.getSubscriberHome() +
                    ", start streaming " + numKeys + " items from feeder");

        try {
            subscription.start(startVLSN);
            fail("Expect IllegalArgumentException raised from subscription");
        } catch (IllegalArgumentException iae) {
            /* expected exception */
            logger.info("Expected exception " + iae.getMessage());
        } finally {
            subscription.shutdown();
        }
    }

    /**
     * Testcase that if the start VLSN to subscribe the replication stream
     * has been cleaned by cleaner and no longer available in the VLSN
     * index at the time of subscription. We expect an entry-not-found message
     * raised in sync-up phase
     *
     * @throws Exception
     */
    @Test
    public void testSubscriptionUnavailableVLSN() throws Exception {

        /* create and verify a replication group */
        prepareTestEnv();

        /* populate some data and verify */
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

        /* delete and update some keys, and clean the log */
        createObsoleteLogs(masterEnv, dbName, startKey, numKeys);
        cleanLog(masterEnv);

        /* check lower end of VLSN index on master */
        verifyVLSNRange(masterEnv);

        /* create a subscription */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeys);
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);

        /* start subscription and we expect InsufficientLogException */
        try {
            subscription.start();
            fail("Expect InsufficientLogException since start VLSN is not " +
                 "available at feeder");
        } catch (InsufficientLogException ile){
            logger.info("Expected InsufficientLogException: " +
                        ile.getMessage());
        } finally {
            subscription.shutdown();
        }
    }

    /**
     * Testcase that subscription callback is able to process commits and
     * aborts from feeder.
     */
    @Test
    public void testTxnCommitsAndAborts() throws Exception {

        numKeys = 1024;
        final int numAborts = 5;

        /* create and verify a replication group */
        prepareTestEnv();
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);
        /* now create some aborts */
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setDurability
            (new Durability(Durability.SyncPolicy.NO_SYNC,
                            Durability.SyncPolicy.NO_SYNC,
                            Durability.ReplicaAckPolicy.SIMPLE_MAJORITY));
        try (Database db = masterEnv.openDatabase(null, dbName, dbConfig)) {
            for (int i = 1; i <= numAborts; i++) {
                IntegerBinding.intToEntry(i, key);
                Transaction txn = masterEnv.beginTransaction(null, txnConfig);
                db.put(txn, key, data);
                txn.abort();
            }
        }

        /* start streaming from the very beginning, should succeed */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeys);
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);
        subscription.start();

        try {
            waitForTestDone(testCbk);
        } catch (TimeoutException e) {
            failTimeout();
        }

        /* expect multiple commits */
        assertTrue("expect commits from loading " + numKeys + " keys",
                   (testCbk.getNumCommits() > 0));
        assertEquals("expect aborts", numAborts, testCbk.getNumAborts());


        /* shutdown test verify we receive all expected keys */
        subscription.shutdown();
    }

    /**
     * Testcase that subscription callback is able to process an exception.
     */
    @Test
    public void testExceptionHandling() throws Exception {

        numKeys = 100;

        /* create and verify a replication group */
        prepareTestEnv();
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

        /* start streaming from the very beginning, should succeed */
        SubscriptionConfig config = createSubConfig(masterEnv, false);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeys,
                                         true); /* allow exception */
        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);
        subscription.start();

        try {
            waitForTestDone(testCbk);
        } catch (TimeoutException e) {
            failTimeout();
        }

        /* now inject an exception into queue */
        testCbk.resetTestDone();
        final String token = "test internal exception";
        Exception exp = new InternalException(token);
        subscription.setExceptionHandlingTestHook(new ExceptionTestHook(exp));

        try {
            waitForTestDone(testCbk);
        } catch (TimeoutException e) {
            failTimeout();
        }

        /* verify injected exception is processed in callback */
        assertEquals("Expect one exception", 1, testCbk.getNumExceptions());
        final Exception lastExp = testCbk.getLastException();
        assertTrue("Expect InternalException",
                   (lastExp != null) && (lastExp instanceof InternalException));
        assertTrue("Expect same token", token.equals(exp.getMessage()));
        subscription.shutdown();
    }

    private void failTimeout() {
        String error = "fail test due to timeout in " +
                       TEST_POLL_TIMEOUT_MS + " ms";
        logger.info(error);
        fail(error);
    }

    /*
     * Create a subscription configuration
     *
     * @param masterEnv     env of master node
     * @param useGroupUUID  true if use a valid group uuid
     *
     * @return a subscription configuration
     * @throws Exception
     */
    private SubscriptionConfig createSubConfig(ReplicatedEnvironment masterEnv,
                                               boolean useGroupUUID)
            throws Exception {
        /* constants and parameters used in test */
        final String home = "./subhome/";
        final String subNodeName = "test-subscriber-node";
        final String nodeHostPortPair = "localhost:6001";

        String feederNode;
        int feederPort;
        String groupName;
        File subHome =
                new File(envRoot.getAbsolutePath() + File.separator + home);
        if (!subHome.exists()) {
            if (subHome.mkdir()) {
                logger.info("create test dir " + subHome.getAbsolutePath());
            } else {
                fail("unable to create test dir, fail the test");
            }
        }

        ReplicationGroup group = masterEnv.getGroup();
        ReplicationNode member = group.getMember(masterEnv.getNodeName());
        feederNode = member.getHostName();
        feederPort = member.getPort();
        groupName = group.getName();

        UUID uuid;
        if (useGroupUUID) {
            uuid = group.getRepGroupImpl().getUUID();
        } else {
            uuid = null;
        }

        String msg = "Feeder is on node " + feederNode + ":" + feederPort +
                     " in replication group "  + groupName +
                     " (group uuid: " + uuid + ")";
        logger.info(msg);

        final String feederHostPortPair = feederNode + ":" + feederPort;


        return new SubscriptionConfig(subNodeName, subHome.getAbsolutePath(),
                                      nodeHostPortPair, feederHostPortPair,
                                      groupName, uuid);
    }

    /* Wait for test done */
    private void waitForTestDone(final SubscriptionTestCallback callBack)
            throws TimeoutException {

        boolean success = new PollCondition(TEST_POLL_INTERVAL_MS,
                                            TEST_POLL_TIMEOUT_MS) {
            @Override
            protected boolean condition() {
               return callBack.isTestDone();
            }
        }.await();

        /* if timeout */
        if (!success) {
            throw new TimeoutException("timeout in polling test ");
        }
    }

    /* Populate data into test db and verify */
    private void populateDataAndVerify(ReplicatedEnvironment masterEnv) {
        createTestData();
        populateDB(masterEnv, dbName, keys);
        readDB(masterEnv, dbName, startKey, numKeys);
        logger.info(numKeys + " records (start key: " +
                    startKey + ") have been populated into db " +
                    dbName + " and verified");
    }

    /*
     * Verify that the lower end of VLSN on master has been bumped up
     * to some value greater than the very first VLSN.
     *
     * @param masterEnv master environment
     */
    private void verifyVLSNRange(ReplicatedEnvironment masterEnv) {
        VLSNIndex vlsnIndex =
            RepInternal.getNonNullRepImpl(masterEnv).getVLSNIndex();
        VLSNRange range = vlsnIndex.getRange();
        logger.info(masterEnv.getNodeName() + ": " + range);
        assertTrue("Lower end of VLSN index should be greater than the" +
                   "very first VLSN",
                   (range.getFirst().compareTo(VLSN.FIRST_VLSN) > 0));
    }

    /* Create a list of (k, v) pairs for testing */
    private void createTestData() {
        for (int i = startKey; i < startKey + numKeys; i++) {
            keys.add(i);
        }
    }

    /* Verify received correct test data from feeder */
    private void verifyTestResults(SubscriptionTestCallback mycbk) {
        List<byte[]> receivedKeys = mycbk.getAllKeys();
        int numKeys = keys.size();

        assertTrue("expect some commits", (mycbk.getNumCommits() > 0));
        assertTrue("expect no aborts", (mycbk.getNumAborts() == 0));

        logger.info("number of keys to verify: " + numKeys);
        assertEquals("number of keys mismatch!", numKeys,
                     receivedKeys.size());

        IntegerBinding binding = new IntegerBinding();
        for (int i = 0; i < keys.size(); i++){
            Integer expectedKey = keys.get(i);
            byte[] receivedKeyByte = receivedKeys.get(i);

            TupleInput tuple = new TupleInput(receivedKeyByte);
            Integer receivedKey = binding.entryToObject(tuple);
            assertEquals("mismatched key!", expectedKey.longValue(),
                         receivedKey.longValue());
        }
        logger.info("successfully verified all " + numKeys + "keys" +
                    ", # commits: " + mycbk.getNumCommits() +
                    ", # aborts: " + mycbk.getNumAborts());
    }

    /*
     * Turn off data cleaner on master to prevent start VLSN from being
     * cleaned. It can be turned on or called explicitly in unit test.
     */
    private void turnOffMasterCleaner() {

        EnvironmentConfig econfig = repEnvInfo[0].getEnvConfig();
        /*
         * Turn off the cleaner, since it's controlled explicitly
         * by the test.
         */
        econfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER,
                               "false");
    }

    /*
     * Put some keys multiple times and delete it, in order to create
     * obsolete entries in log files so cleaner can clean it
     *
     * @param master master node
     * @param dbName test db name
     * @param startKey  start key to put and delete
     * @param numKeys   number of keys to put and delete
     */
    private void createObsoleteLogs(ReplicatedEnvironment master,
                                    String dbName,
                                    int startKey,
                                    int numKeys) {
        final int repeatPutTimes = 10;

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry data = new DatabaseEntry(new byte[100]);

        TransactionConfig txnConfig = new TransactionConfig();
        txnConfig.setDurability
            (new Durability(Durability.SyncPolicy.NO_SYNC,
                            Durability.SyncPolicy.NO_SYNC,
                            Durability.ReplicaAckPolicy.SIMPLE_MAJORITY));


        try (Database db = master.openDatabase(null, dbName, dbConfig)) {
            for (int i = startKey; i < numKeys; i++) {
                IntegerBinding.intToEntry(i, key);
                Transaction txn = master.beginTransaction(null, txnConfig);
                for (int repeat = 0; repeat < repeatPutTimes; repeat++) {
                    db.put(txn, key, data);
                }
                db.delete(txn, key);
                txn.commit();
            }

            /* One more synchronous one to flush the log files. */
            IntegerBinding.intToEntry(startKey, key);
            txnConfig.setDurability
                (new Durability(Durability.SyncPolicy.SYNC,
                                Durability.SyncPolicy.SYNC,
                                Durability.ReplicaAckPolicy.SIMPLE_MAJORITY));
            Transaction txn = master.beginTransaction(null, txnConfig);
            db.put(txn, key, data);
            db.delete(txn, key);
            txn.commit();
        }
    }

    private void cleanLog(ReplicatedEnvironment repEnv) {
        if (repEnv == null) {
            return;
        }

        CheckpointConfig force = new CheckpointConfig();
        force.setForce(true);

        EnvironmentStats stats = repEnv.getStats(new StatsConfig());
        int numCleaned;
        int cleanedThisRun;
        long beforeNFileDeletes = stats.getNCleanerDeletions();

        /* clean logs */
        numCleaned = 0;
        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        while ((cleanedThisRun = repEnv.cleanLog()) > 0) {
            numCleaned += cleanedThisRun;
        }
        repEnv.checkpoint(force);

        stats = repEnv.getStats(new StatsConfig());
        long afterNFileDeletes = stats.getNCleanerDeletions();
        long actualDeleted = afterNFileDeletes - beforeNFileDeletes;
        repEnv.checkpoint(force);

        logger.info(
            repEnv.getNodeName() + ", cbvlsn=" +
            RepInternal.getNonNullRepImpl(repEnv).
                getRepNode().getGroupCBVLSN() +
            " deletedFiles=" + actualDeleted + " numCleaned=" + numCleaned);
    }

    /**
     * Create a test env and verify it is in good shape
     *
     * @throws InterruptedException
     */
    private void prepareTestEnv() throws InterruptedException {

        createGroup(numDataNodes);

        if (hasMonitor) {
            /* monitor is the last node in group */
            ReplicationConfig rConfig =
                    repEnvInfo[groupSize - 1].getRepConfig();
            rConfig.setNodeType(NodeType.MONITOR);
            MonitorConfig monConfig = new MonitorConfig();
            monConfig.setNodeName(rConfig.getNodeName());
            monConfig.setGroupName(rConfig.getGroupName());
            monConfig.setNodeHostPort(rConfig.getNodeHostPort());
            monConfig.setHelperHosts(rConfig.getHelperHosts());

            ReplicationConfig r0Config =
                    repEnvInfo[0].getEnv().getRepConfig();
            monConfig.setRepNetConfig(r0Config.getRepNetConfig());
            monitor = new Monitor(monConfig);
            monitor.register();
        } else {
            monitor = null;
        }

        for (int i=0; i < numDataNodes; i++) {
            final ReplicatedEnvironment env = repEnvInfo[i].getEnv();
            final boolean isMaster = (env.getState() == MASTER);
            final int targetGroupSize = groupSize;

            ReplicationGroup group = null;
            for (int j=0; j < 100; j++) {
                group = env.getGroup();
                if (group.getNodes().size() == targetGroupSize) {
                    break;
                }
                /* Wait for the replica to catch up. */
                Thread.sleep(1000);
            }
            assertEquals("Nodes", targetGroupSize, group.getNodes().size());
            assertEquals(RepTestUtils.TEST_REP_GROUP_NAME, group.getName());
            for (RepTestUtils.RepEnvInfo rinfo : repEnvInfo) {
                final ReplicationConfig repConfig = rinfo.getRepConfig();
                ReplicationNode member =
                        group.getMember(repConfig.getNodeName());

                /* only log the group and nodes in group for master */
                if (isMaster) {
                    logger.info("group: " + group.getName() +
                            " node: " + member.getName() +
                            " type: " + member.getType() +
                            " socket addr: " + member.getSocketAddress());
                }

                assertNotNull("Member", member);
                assertEquals(repConfig.getNodeName(), member.getName());
                assertEquals(repConfig.getNodeType(), member.getType());
                assertEquals(repConfig.getNodeSocketAddress(),
                            member.getSocketAddress());
            }

            /* verify monitor */
            final Set<ReplicationNode> monitorNodes = group.getMonitorNodes();
            for (final ReplicationNode n : monitorNodes) {
                assertEquals(NodeType.MONITOR, n.getType());
            }
            if (hasMonitor) {
                assertEquals("Monitor nodes", 1, monitorNodes.size());
                logger.info("monitor verified");
            }

            /* verify data nodes */
            final Set<ReplicationNode> dataNodes = group.getDataNodes();
            for (final ReplicationNode n : dataNodes) {
                assertEquals(NodeType.ELECTABLE, n.getType());
            }
            logger.info("data nodes verified");
        }
    }

    /* test helper to test subscription with or without group uuid */
    private void testGroupUUIDhelper(boolean useGroupUUID) throws Exception {

        /* turn off data cleaner on master */
        turnOffMasterCleaner();

        /* create and verify a replication group */
        prepareTestEnv();

        /* populate test db and verify */
        ReplicatedEnvironment masterEnv = repEnvInfo[0].getEnv();
        populateDataAndVerify(masterEnv);

        /* create a subscription with a valid group uuid */
        SubscriptionConfig config = createSubConfig(masterEnv, useGroupUUID);
        SubscriptionTestCallback testCbk =
            new SubscriptionTestCallback(numKeys);

        config.setCallback(testCbk);
        subscription = new Subscription(config, logger);

        /* start streaming from the very beginning, should succeed */
        subscription.start();

        try {
            waitForTestDone(testCbk);
        } catch (TimeoutException e) {
            failTimeout();
        }
        /* shutdown test verify we receive all expected keys */
        subscription.shutdown();
        verifyTestResults(testCbk);
    }

    private class SubscriptionTestCallback implements SubscriptionCallback {


        private final int numKeysExpected;
        private final boolean allowException;

        private int numCommits;
        private int numAborts;
        private int numExceptions;
        private Exception lastException;
        private List<byte[]> recvKeys;
        private VLSN firstVLSN;
        private boolean testDone;

        SubscriptionTestCallback(int numKeysExpected, boolean allowException) {
            this.numKeysExpected = numKeysExpected;
            this.allowException = allowException;
            numAborts = 0;
            numCommits = 0;
            recvKeys = new ArrayList<>();
            firstVLSN = VLSN.NULL_VLSN;
            testDone = false;
        }

        /* callback does not allow exception */
        SubscriptionTestCallback(int numKeysExpected) {
            this(numKeysExpected, false);
        }

        @Override
        public void processPut(VLSN vlsn, byte[] key, byte[] value,
                               long txnId) {
            processPutAndDel(vlsn, key);
        }

        @Override
        public void processDel(VLSN vlsn, byte[] key, long txnId) {
            processPutAndDel(vlsn, key);
        }

        @Override
        public void processCommit(VLSN vlsn, long txnId) {
            numCommits++;
        }

        @Override
        public void processAbort(VLSN vlsn, long txnId) {
            numAborts++;
        }

        @Override
        public void processException(Exception exception) {
            assert (exception != null);

            if (allowException) {
                numExceptions++;
                lastException = exception;
            } else {
                /* fail test if we do not expect any exception*/
                fail(exception.getMessage());
            }
            testDone = true;
        }

        public void resetTestDone() {
            testDone = false;
        }

        public boolean isTestDone() {
            return testDone;
        }

        public List<byte[]> getAllKeys() {
            return recvKeys;
        }

        public VLSN getFirstVLSN() {
            return firstVLSN;
        }

        public int getNumCommits() {
            return numCommits;
        }

        public int getNumAborts() {
            return numAborts;
        }

        public int getNumExceptions() {
            return numExceptions;
        }

        public Exception getLastException() {
            return lastException;
        }

        private void processPutAndDel(VLSN vlsn, byte[] key) {

            /* record the first VLSN received from feeder */
            if (firstVLSN.isNull()) {
                firstVLSN = vlsn;
            }

            if (recvKeys.size() < numKeysExpected) {
                recvKeys.add(key);

                logger.finest("vlsn: " + vlsn +
                              "key " + Key.dumpString(key, 0) +
                              ", # of keys received: " + recvKeys.size() +
                              ", expected: " + numKeysExpected);

                if (recvKeys.size() == numKeysExpected) {
                    logger.info("received all " + numKeysExpected + " keys.");
                    testDone = true;
                }

            } else {
                /*
                 * we may receive more keys because in some tests,  the # of
                 * keys expected could be less than the size of database, but
                 * they are not interesting to us so ignore.
                 */
                logger.finest("keys beyond expected " + numKeysExpected +
                              " keys, vlsn: " + vlsn +
                              ", key: " + Key.dumpString(key, 0));
            }
        }

    }

    private class ExceptionTestHook
        extends TestHookAdapter<SubscriptionThread> {

        private Exception e;

        ExceptionTestHook(Exception e) {
            this.e = e;
        }

        @Override
        public void doHook(final SubscriptionThread subscriptionThread) {
            try {
                subscriptionThread.offer(e);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }

        @Override
        public SubscriptionThread getHookValue() {
            throw new UnsupportedOperationException();
        }
    }
}
