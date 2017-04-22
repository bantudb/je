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

package com.sleepycat.je.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Calendar;
import java.util.TimerTask;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.je.util.verify.DataVerifier;
import com.sleepycat.je.utilint.CronScheduleParser;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/*
 * Test the data corruption caused by media/disk failure. Btree corruption
 * verification will be implemented in next release.
 */
public class DataVerifierTest extends TestBase {

    private static final String DB_NAME = "tempDB";

    private Environment env;
    private Database db;
    private File envHome;
    private Cursor c;

    private final int recNum = 1000 * 50; //(1000 * 500) * 50 files
    private final int dataLen = 500;
    private final int totalWaitTries = 100;

    private static long millsOneDay = 24 * 60 * 60 * 1000;
    private static long millsOneHour = 60 * 60 * 1000;
    private static long millsOneMinute = 60 * 1000;

    private static final EnvironmentConfig envConfigWithVerifier
        = initConfig();
    private static final EnvironmentConfig envConfigWithoutVerifier
        = initConfig();

    static {
        envConfigWithoutVerifier.setConfigParam(
            EnvironmentParams.ENV_RUN_VERIFIER.getName(), "false");
    }

    @Before
    public void setUp() 
        throws Exception {
        envHome = SharedTestUtils.getTestDir();
        super.setUp();
    }

    @After
    public void tearDown() 
        throws Exception {
        CronScheduleParser.setCurCalHook = null;

        if (c != null) {
            try {
                c.close(); 
            } catch (EnvironmentFailureException efe) {

            }
            c = null;
        }

        if (db != null) {
            try {
                db.close(); 
            } catch (EnvironmentFailureException efe) {

            }
            db = null;
        }

        if (env != null) {
            env.close();
            env = null;
        }

        super.tearDown();
    }

    /**
     * Test config via EnvironmentConfig.
     */
    @Test
    public void testConfig() {

        checkConfig(null, millsOneDay - millsOneMinute, millsOneDay);

        checkConfig("5 * * * *", 4 * millsOneMinute, millsOneHour);

        checkConfig("* * * * *", 0, millsOneMinute);

        checkConfig(
            "10 22 * * 6",
            9 * millsOneMinute + 22 * millsOneHour + 1 * millsOneDay,
            7 * millsOneDay);

        checkConfig(
            "10 22 * * 3",
            7 * millsOneDay -
            (51 * millsOneMinute + 1 * millsOneHour + 1 * millsOneDay),
            7 * millsOneDay);
    }

    private void checkConfig(String cronSchedule, long delay, long interval) {

        EnvironmentConfig envConfig = initConfig();
        /*
         * For current test, in order to let DataVerifier to run
         * during the JE Standalone test, so I set the default value of
         * VERIFY_SCHEDULE to be "0 * * * *". 
         * 
         * In future, I may set this value in each JE Standalone test and
         * recover the default value to "0 0 * * *" even if when testing.
         * 
         * For now, when we test configuration, we set it to be normal default
         * value, i.e. "0 0 * * *".
         * 
         */
        envConfig.setConfigParam(
            EnvironmentConfig.VERIFY_SCHEDULE, "0 0 * * *");

        if (cronSchedule != null) {
            envConfig.setConfigParam(
                EnvironmentConfig.VERIFY_SCHEDULE, cronSchedule);
        }

        MyHook hook = new MyHook();
        CronScheduleParser.setCurCalHook = hook;

        env = new Environment(envHome, envConfig);

        DataVerifier verifier =
            DbInternal.getEnvironmentImpl(env).getDataVerifier();

        assertNotNull(verifier);
        assertEquals(delay, verifier.getVerifyDelay());
        assertEquals(interval, verifier.getVerifyInterval());
    
        env.close();
        env = null;
    }

    @Test
    public void testConfigChange() {
        EnvironmentConfig envConfig = initConfig();
        envConfig.setConfigParam(
            EnvironmentConfig.VERIFY_SCHEDULE, "0 0 * * *");

        MyHook hook = new MyHook();
        CronScheduleParser.setCurCalHook = hook;

        env = new Environment(envHome, envConfig);

        DataVerifier verifier =
            DbInternal.getEnvironmentImpl(env).getDataVerifier();

        /*
         * The default VERIFY_SCHEDULE "0 0 * * *"
         */
        assertNotNull(verifier);
        assertNotNull(verifier.getVerifyTask());
        assertNotNull(verifier.getCronSchedule());
        assertEquals(millsOneDay - millsOneMinute, verifier.getVerifyDelay());
        assertEquals(millsOneDay, verifier.getVerifyInterval());
        TimerTask oldVerifyTask  = verifier.getVerifyTask();
        String oldCronSchedule = verifier.getCronSchedule();

        /*
         * The default VERIFY_SCHEDULE "0 0 * * *"
         */
        envConfig.setConfigParam(
            EnvironmentConfig.VERIFY_SCHEDULE, "5 * * * *");
        env.setMutableConfig(envConfig);
        assertNotNull(verifier);
        assertNotNull(verifier.getVerifyTask());
        assertNotSame(oldVerifyTask, verifier.getVerifyTask());
        assertNotNull(verifier.getCronSchedule());
        assertNotSame(oldCronSchedule, verifier.getCronSchedule());
        assertEquals(4 * millsOneMinute, verifier.getVerifyDelay());
        assertEquals(millsOneHour, verifier.getVerifyInterval());

        /*
         * Disable ENV_RUN_VERIFIER.
         */
        envConfig.setConfigParam(
            EnvironmentConfig.ENV_RUN_VERIFIER, "false");
        env.setMutableConfig(envConfig);
        assertNotNull(verifier);
        assertNull(verifier.getVerifyTask());
        assertNull(verifier.getCronSchedule());
        assertEquals(0, verifier.getVerifyDelay());
        assertEquals(0, verifier.getVerifyInterval());

        env.close();
        env = null;
    }

    class MyHook implements TestHook<Void> {

        @Override
        public void doHook() {
            /*
             * Set the current Calendar to be 00:01 Friday.
             */
            Calendar generatedCurCal = Calendar.getInstance();
            generatedCurCal.set(Calendar.DAY_OF_WEEK, 6);
            generatedCurCal.set(Calendar.HOUR_OF_DAY, 0);
            generatedCurCal.set(Calendar.MINUTE, 1);
            generatedCurCal.set(Calendar.SECOND, 0);
            generatedCurCal.set(Calendar.MILLISECOND, 0);

            CronScheduleParser.curCal = generatedCurCal;
        }

        @Override
        public void doHook(Void obj) {
        }
        @Override
        public void hookSetup() {
        }
        @Override
        public void doIOHook() throws IOException {
        }
        @Override
        public Void getHookValue() {
            return null;
        }
    }

    private static EnvironmentConfig initConfig() {
        EnvironmentConfig config = TestUtils.initEnvConfig();
        config.setAllowCreate(true);
        config.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
        config.setConfigParam(EnvironmentConfig.ENV_RUN_EVICTOR, "false");
        config.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER,
            "false");
        config.setConfigParam(EnvironmentConfig.ENV_RUN_IN_COMPRESSOR,
            "false");
        config.setCacheSize(1000000);
        config.setConfigParam(EnvironmentConfig.LOG_FILE_MAX, "1000000");
        config.setConfigParam(EnvironmentConfig.VERIFY_SCHEDULE, "* * * * *");
        return config;
    }

    @Test
    public void testDataCorruptWithVerifier() {
        System.out.println("testDataCorruptWithVerifier");
        testDataCorruptionVerifierInternal(envConfigWithVerifier);
    }

    @Test
    public void testDataCorruptWithoutVerifier() {
        System.out.println("testDataCorruptWithoutVerifier");
        testDataCorruptionVerifierInternal(envConfigWithoutVerifier);
    }

    private void testDataCorruptionVerifierInternal(EnvironmentConfig config) {
        openEnvAndDb(config);
        initialDb();
        /* The first pass traverse to add file handles to fileCache. */
        transverseDb(false);
        createDataCorrupt();
        transverseDb(true);
    }

    public void openEnvAndDb(EnvironmentConfig config) {
        env = new Environment(envHome, config);

        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        db = env.openDatabase(null, DB_NAME, dbConfig);

        c = db.openCursor(null, null);
    }

    public void initialDb() {
        try {
            for (int i = 0 ; i < recNum; i++) {
                final DatabaseEntry key = new DatabaseEntry();
                IntegerBinding.intToEntry(i, key);
                final DatabaseEntry data = new DatabaseEntry(new byte[dataLen]);
                db.put(null, key, data);
            }
        } catch (DatabaseException dbe) {
            throw new RuntimeException("Initiate Database fails.", dbe);
        }

        final int totalFiles =
            DbInternal.getEnvironmentImpl(env).getFileManager().                            
            getAllFileNumbers().length;
        assert totalFiles < 100 : "Total file number is " + totalFiles;
    }

    public void createDataCorrupt() {
        final EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        final FileManager fm = envImpl.getFileManager();
        final File[] files = fm.listJDBFiles();
        File choosenFile = null;
        try {
            for (File file : files) {
                //System.out.println(file.getCanonicalPath());
                if (file.getCanonicalPath().contains("00000002.jdb")) {
                    choosenFile = file;
                    break;
                }
            }

            RandomAccessFile rafile = new RandomAccessFile(choosenFile, "rw");
            long fileLength = rafile.length();
            rafile.seek(fileLength / 2);
            byte b = rafile.readByte();
            if (b == 255) {
                b = (byte)(b - 1);
            } else {
                b = (byte)(b + 1);
            }
            rafile.seek(fileLength / 2);
            rafile.writeByte(b);
            rafile.close(); 
        } catch (Exception e) {
            throw new RuntimeException("Create data corruption fails.", e);
        }
    }

    /*
     * The first pass transverse aims to cache all the log files. The second
     * pass transverse aims to check whether the Read operation can succeed
     * when one log file is corrupted, depending on whether ENV_RUN_VERIFIER
     * is set.
     * 
     */
    public void transverseDb(boolean check) {
        boolean verify = DbInternal.getEnvironmentImpl(env).getConfigManager().
            getBoolean(EnvironmentParams.ENV_RUN_VERIFIER);
        try {
            final DatabaseEntry key = new DatabaseEntry();
            final DatabaseEntry data = new DatabaseEntry();

            int recordCount = 0;
            int firstKey;
            do {
                if (!check) {
                    firstKey = 0;
                } else {
                    firstKey = 20000;
                }
                IntegerBinding.intToEntry(firstKey, key);

                assert c.getFirst(key, data, null) == OperationStatus.SUCCESS :
                    "The db should contain this record: key is " + firstKey;

                if (!check) {
                    while (c.getNext(key, data, null) ==
                        OperationStatus.SUCCESS) {
                        // Do nothing.
                    }
                }
                /*
                 * The smallest interval of the VERIFY_SCHEDULE is 1 minutes,
                 * so here we try to sleep 1s for totalWaitTries times to
                 * guarantee that the data corruption verifier task run at
                 * least once.
                 */
                try {Thread.sleep(1000);} catch (Exception e) {}
            } while (check && ++recordCount < totalWaitTries);

            if (check) {
                if (verify) {
                    fail("With verifying data corruption, we should catch" +
                        "EnvironmentFailureException.");
                }
            }
        } catch (EnvironmentFailureException efe) {
            assertTrue(efe.isCorrupted());
            if (check) {
                if (!verify) {
                    fail("Without verifying data corruption, we should" +
                        "not catch EnvironmentFailureException");
                }
            }
            // Leave tearDown() to close cursor, db and env.
        }
    }
}