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

package com.sleepycat.je.util.verify;

import static com.sleepycat.je.config.EnvironmentParams.ENV_RUN_VERIFIER;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_SCHEDULE;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_LOG;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_SECONDARIES;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_BTREE;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_DATA_RECORDS;
import static com.sleepycat.je.config.EnvironmentParams.VERIFY_OBSOLETE_RECORDS;

import java.util.Timer;
import java.util.TimerTask;

import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.util.DbVerifyLog;
import com.sleepycat.je.util.LogVerificationException;
import com.sleepycat.je.utilint.CronScheduleParser;
import com.sleepycat.je.utilint.StoppableThread;

/**
 *  Periodically perform checksum verification, Btree verification, or both,
 *  depending on {@link com.sleepycat.je.EnvironmentConfig#VERIFY_LOG} and
 *  {@link com.sleepycat.je.EnvironmentConfig#VERIFY_BTREE}.
 *  
 *  The first-time start time and the period of the verification is determined
 *  by {@link com.sleepycat.je.EnvironmentConfig#VERIFY_SCHEDULE}.
 *  
 *  For current version, JE only implements checksum verification feature. The
 *  Btree verification feature will be implemented in next release.
 */
public class DataVerifier {
    private final EnvironmentImpl envImpl;
    private final Timer timer;
    private VerifyTask verifyTask;
    private final DbVerifyLog dbLogVerifier;

    private long verifyDelay;
    private long verifyInterval;
    private String cronSchedule;

    private boolean shutdownRequest = false;

    public DataVerifier(EnvironmentImpl envImpl) {

        this.envImpl = envImpl;
        this.timer = new Timer(
            envImpl.makeDaemonThreadName(
                Environment.DATA_CORRUPTION_VERIFIER_NAME),
            true /*isDaemon*/);
        dbLogVerifier = new DbVerifyLog(envImpl, 0);
    }

    /**
     * Applies the new configuration, then cancels and reschedules the verify
     * task as needed.
     */
    public void configVerifyTask(DbConfigManager configMgr) {

        if (!updateConfig(configMgr)) {
            return;
        }

        synchronized (this) {
            if (!shutdownRequest) {
                cancel();

                if (cronSchedule != null) {
                    verifyTask = new VerifyTask(
                        envImpl,
                        configMgr.getBoolean(VERIFY_LOG),
                        configMgr.getBoolean(VERIFY_BTREE),
                        configMgr.getBoolean(VERIFY_SECONDARIES),
                        configMgr.getBoolean(VERIFY_DATA_RECORDS),
                        configMgr.getBoolean(VERIFY_OBSOLETE_RECORDS));

                    timer.schedule(verifyTask, verifyDelay, verifyInterval);
                }
            }
        }
    }

    private void cancel() {
        if (verifyTask != null) {
            verifyTask.cancel();
            verifyTask = null;
        }
    }

    public void requestShutdown() {
        shutdown();
    }

    public void shutdown() {
        synchronized (this) {
            shutdownRequest = true;
            cancel();
            timer.cancel();
            dbLogVerifier.setStopVerifyFlag();
        }
    }

    public long getVerifyDelay() {
        return verifyDelay;
    }

    public long getVerifyInterval() {
        return verifyInterval;
    }

    public VerifyTask getVerifyTask() {
        return verifyTask;
    }

    public String getCronSchedule() {
        return cronSchedule;
    }

    /**
     * Applies the new configuration and returns whether it changed.
     */
    private boolean updateConfig(DbConfigManager configMgr) {

        String newCronSchedule = configMgr.get(VERIFY_SCHEDULE);

        /*
         * If set to false (which is not the default).
         */
        if (!configMgr.getBoolean(ENV_RUN_VERIFIER)) {
            newCronSchedule = null;
            if (cronSchedule == null) {
                return false;
            }
            cronSchedule = null;
            verifyDelay = 0;
            verifyInterval = 0;
            return true;
        } else {
            if (CronScheduleParser.checkSame(cronSchedule, newCronSchedule)) {
                return false;
            }
            CronScheduleParser csp = new CronScheduleParser(newCronSchedule);
            verifyDelay = csp.getDelayTime();
            verifyInterval = csp.getInterval();
            cronSchedule = newCronSchedule;
            return true;
        }
    }

    class VerifyTask extends TimerTask {
        private final EnvironmentImpl envImpl;
        private final boolean verifyLog;
        private final boolean verifyBtree;
        private final boolean verifySecondaries;
        private final boolean verifyDataRecords;
        private final boolean verifyObsoleteRecords;

        VerifyTask(
            EnvironmentImpl envImpl,
            boolean verifyLog,
            boolean verifyBtree,
            boolean verifySecondaries,
            boolean verifyDataRecords,
            boolean verifyObsoleteRecords) {

            this.envImpl = envImpl;
            this.verifyLog = verifyLog;
            this.verifyBtree = verifyBtree;
            this.verifySecondaries = verifySecondaries;
            this.verifyDataRecords = verifyDataRecords;
            this.verifyObsoleteRecords = verifyObsoleteRecords;
        }

        @Override
        public void run() {
            boolean success = false;
            try {
                /*
                 * Now only consider the data corruption caused by
                 * media/disk failure. Btree corruption will be verified
                 * in next release.
                 */
                if (verifyLog) {
                    dbLogVerifier.verifyAll();
                }

                /*
                if (verifyBtree) {
 
                }

                if (verifySecondaries) {

                }

                if (verifyDataRecords) {

                }

                if (verifyObsoleteRecords) {

                }
                */
                success = true;
            } catch (LogVerificationException lve) {
                new EnvironmentFailureException(
                    envImpl,
                    EnvironmentFailureReason.LOG_CHECKSUM,
                    "Corruption detected by log verifier",
                    lve /*LogVerificationException*/);
            } catch (EnvironmentFailureException efe) {
                // Do nothing. Just cancel this timer in finally.
            } catch (Throwable e) {
                if (envImpl.isValid()) {
                    StoppableThread.handleUncaughtException(
                        envImpl.getLogger(), envImpl, Thread.currentThread(),
                        e);
                }
            } finally {
                if (!success) {
                    shutdown();
                }
            }
        }
    }
}