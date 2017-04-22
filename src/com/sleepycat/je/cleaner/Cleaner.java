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

package com.sleepycat.je.cleaner;

import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_BIN_DELTAS_CLEANED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_BIN_DELTAS_DEAD;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_BIN_DELTAS_MIGRATED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_BIN_DELTAS_OBSOLETE;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_CLUSTER_LNS_PROCESSED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_DELETIONS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_DISK_READS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_ENTRIES_READ;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_INS_CLEANED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_INS_DEAD;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_INS_MIGRATED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_INS_OBSOLETE;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNQUEUE_HITS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_CLEANED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_DEAD;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_EXPIRED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_LOCKED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_MARKED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_MIGRATED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_LNS_OBSOLETE;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_MARKED_LNS_PROCESSED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_MAX_UTILIZATION;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_MIN_UTILIZATION;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_PENDING_LNS_LOCKED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_PENDING_LNS_PROCESSED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_REPEAT_ITERATOR_READS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_REVISAL_RUNS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_RUNS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_TOTAL_LOG_SIZE;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_TO_BE_CLEANED_LNS_PROCESSED;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.CLEANER_TWO_PASS_RUNS;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.GROUP_DESC;
import static com.sleepycat.je.cleaner.CleanerStatDefinition.GROUP_NAME;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.cleaner.FileSelector.CheckpointStartCleanerState;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.CursorImpl;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.DbTree;
import com.sleepycat.je.dbi.EnvConfigObserver;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.log.LogItem;
import com.sleepycat.je.log.ReplicationContext;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.FileSummaryLN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.tree.LN;
import com.sleepycat.je.tree.Node;
import com.sleepycat.je.tree.Tree;
import com.sleepycat.je.tree.TreeLocation;
import com.sleepycat.je.txn.BasicLocker;
import com.sleepycat.je.txn.LockGrantType;
import com.sleepycat.je.txn.LockManager;
import com.sleepycat.je.txn.LockResult;
import com.sleepycat.je.txn.LockType;
import com.sleepycat.je.utilint.DaemonRunner;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.FormatUtil;
import com.sleepycat.je.utilint.IntStat;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.LongStat;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.je.utilint.VLSN;

/**
 * The Cleaner is responsible for effectively garbage collecting the JE log.
 * It selects the least utilized log file for cleaning (see FileSelector),
 * reads through the log file (FileProcessor) and determines whether each entry
 * is obsolete (no longer relevant) or active (referenced by the Btree).
 * Entries that are active are migrated (copied) to the end of the log, and
 * finally the cleaned file is deleted.
 *
 * The migration of active entries is a multi-step process that can be
 * configured to operate in different ways.  Eviction and checkpointing, as
 * well as the cleaner threads (FileProcessor instances) are participants in
 * this process.  Migration may be immediate or lazy.
 *
 * Active INs are always migrated lazily, which means that they are marked
 * dirty by the FileProcessor, and then logged later by an eviction or
 * checkpoint.  Active LNs are always migrated immediately by the FileProcessor
 * by logging them.
 *
 * When the FileProcessor is finished with a file, all lazy migration for that
 * file is normally completed by the end of the next checkpoint, if not sooner
 * via eviction.  The checkpoint/recovery mechanism will ensure that obsolete
 * entries will not be referenced by the Btree.  At the end of the checkpoint,
 * it is therefore safe to delete the log file.
 *
 * There is one exception to the above paragraph.  When attempting to migrate
 * an LN, if the LN cannot be locked then we must retry the migration at a
 * later time.  Also, if a database removal is in progress, we consider all
 * entries in the database obsolete but cannot delete the log file until
 * database removal is complete.  Such "pending" LNs and databases are queued
 * and processed periodically during file processing and at the start of a
 * checkpoint; see processPending().  In this case, we may have to wait for
 * more than one checkpoint to occur before the log file can be deleted.  See
 * FileSelector and the use of the pendingLNs and pendingDBs collections.
 */
public class Cleaner implements DaemonRunner, EnvConfigObserver {
    /* From cleaner */
    static final String CLEAN_IN = "CleanIN:";
    static final String CLEAN_LN = "CleanLN:";
    static final String CLEAN_PENDING_LN = "CleanPendingLN:";

    /**
     * The CacheMode to use for Btree searches.  This is currently UNCHANGED
     * because we update the generation of the BIN when we migrate an LN.
     * In other other cases, it is not desirable to keep INs in cache.
     */
    static final CacheMode UPDATE_GENERATION = CacheMode.UNCHANGED;

    /**
     * Whether the cleaner should participate in critical eviction.  Ideally
     * the cleaner would not participate in eviction, since that would reduce
     * the cost of cleaning.  However, the cleaner can add large numbers of
     * nodes to the cache.  By not participating in eviction, other threads
     * could be kept in a constant state of eviction and would effectively
     * starve.  Therefore, this setting is currently enabled.
     */
    static final boolean DO_CRITICAL_EVICTION = true;

    /*
     * Constants used by checkBacklogGrowth.  These settings are not
     * configurable externally because our internal backlog data will probably
     * be removed or changed in the future, and we'll have to use a different
     * approach for determining whether the cleaner is making progress.
     */
    /* Number of backlogs counted in the trailing average. */
    static final int BACKLOG_ALERT_COUNT = 5;
    /* Smallest backlog value that will generate an alert. */
    static final int BACKLOG_ALERT_FLOOR = 5;

    private static final String DELETED_SUBDIR = "deleted";

    /*
     * Used to reduce repetitions of the same message about protected files.
     */
    private volatile SortedSet<Long> lastProtectedFileSet = null;
    private volatile String lastProtectedFileMsg = null;

    /*
     * Cumulative counters.  Updates to these counters occur in multiple
     * threads, including FileProcessor threads,  and are not synchronized.
     * This could produce errors in counting, but avoids contention around stat
     * updates.
     */
    StatGroup stats;
    LongStat nCleanerRuns;
    LongStat nTwoPassRuns;
    LongStat nRevisalRuns;
    LongStat nCleanerDeletions;
    LongStat nINsObsolete;
    LongStat nINsCleaned;
    LongStat nINsDead;
    LongStat nINsMigrated;
    LongStat nBINDeltasObsolete;
    LongStat nBINDeltasCleaned;
    LongStat nBINDeltasDead;
    LongStat nBINDeltasMigrated;
    LongStat nLNsObsolete;
    LongStat nLNsExpired;
    LongStat nLNsCleaned;
    LongStat nLNsDead;
    LongStat nLNsLocked;
    LongStat nLNsMigrated;
    LongStat nLNsMarked;
    LongStat nLNQueueHits;
    LongStat nPendingLNsProcessed;
    LongStat nMarkedLNsProcessed;
    LongStat nToBeCleanedLNsProcessed;
    LongStat nClusterLNsProcessed;
    LongStat nPendingLNsLocked;
    LongStat nEntriesRead;
    LongStat nDiskReads;
    LongStat nRepeatIteratorReads;
    LongStat totalLogSize;
    IntStat currentMinUtilization;
    IntStat currentMaxUtilization;

    /*
     * Configuration parameters are non-private for use by FileProcessor,
     * UtilizationTracker, or UtilizationCalculator.
     */
    long lockTimeout;
    int readBufferSize;
    int lookAheadCacheSize;
    long nDeadlockRetries;
    boolean expunge;
    boolean useDeletedDir;
    int twoPassGap;
    int twoPassThreshold;
    boolean gradualExpiration;
    long cleanerBytesInterval;
    boolean trackDetail;
    boolean fetchObsoleteSize;
    int dbCacheClearCount;
    private final boolean rmwFixEnabled;
    int minUtilization;
    int minFileUtilization;
    int minAge;

    private final String name;
    private final EnvironmentImpl env;
    private final UtilizationProfile profile;
    private final UtilizationTracker tracker;
    private final ExpirationProfile expirationProfile;
    private final UtilizationCalculator calculator;
    private final FileSelector fileSelector;
    private FileProcessor[] threads;

    /*
     * Log file deletion must check for ongoing backups and other procedures
     * that rely on a set log files remaining stable (no deletions).  Multiple
     * ranges of file numbers may be protected from deletion, where each range
     * is from a given file number to the end of the log.
     *
     * protectedFileRanges is a list that contains the starting file number for
     * each protected range.  All files from the mininum of these values to the
     * end of the log are protected from deletion.  This field is accessed only
     * while synchronizing on protectedFileRanges.
     */
    private final List<Long> protectedFileRanges;
    private final Logger logger;
    final AtomicLong totalRuns;
    TestHook fileChosenHook;

    /** @see #processPending */
    private final AtomicBoolean processPendingReentrancyGuard =
        new AtomicBoolean(false);

    /** @see #wakeupAfterWrite */
    private final AtomicLong bytesWrittenSinceActivation = new AtomicLong(0);

    public Cleaner(EnvironmentImpl env, String name) {
        this.env = env;
        this.name = name;

        /* Initiate the stats definitions. */
        stats = new StatGroup(GROUP_NAME, GROUP_DESC);
        nCleanerRuns = new LongStat(stats, CLEANER_RUNS);
        nTwoPassRuns = new LongStat(stats, CLEANER_TWO_PASS_RUNS);
        nRevisalRuns = new LongStat(stats, CLEANER_REVISAL_RUNS);
        nCleanerDeletions = new LongStat(stats, CLEANER_DELETIONS);
        nINsObsolete = new LongStat(stats, CLEANER_INS_OBSOLETE);
        nINsCleaned = new LongStat(stats, CLEANER_INS_CLEANED);
        nINsDead = new LongStat(stats, CLEANER_INS_DEAD);
        nINsMigrated = new LongStat(stats, CLEANER_INS_MIGRATED);
        nBINDeltasObsolete = new LongStat(stats, CLEANER_BIN_DELTAS_OBSOLETE);
        nBINDeltasCleaned = new LongStat(stats, CLEANER_BIN_DELTAS_CLEANED);
        nBINDeltasDead = new LongStat(stats, CLEANER_BIN_DELTAS_DEAD);
        nBINDeltasMigrated = new LongStat(stats, CLEANER_BIN_DELTAS_MIGRATED);
        nLNsObsolete = new LongStat(stats, CLEANER_LNS_OBSOLETE);
        nLNsExpired = new LongStat(stats, CLEANER_LNS_EXPIRED);
        nLNsCleaned = new LongStat(stats, CLEANER_LNS_CLEANED);
        nLNsDead = new LongStat(stats, CLEANER_LNS_DEAD);
        nLNsLocked = new LongStat(stats, CLEANER_LNS_LOCKED);
        nLNsMigrated = new LongStat(stats, CLEANER_LNS_MIGRATED);
        nLNsMarked = new LongStat(stats, CLEANER_LNS_MARKED);
        nLNQueueHits = new LongStat(stats, CLEANER_LNQUEUE_HITS);
        nPendingLNsProcessed =
            new LongStat(stats, CLEANER_PENDING_LNS_PROCESSED);
        nMarkedLNsProcessed = new LongStat(stats, CLEANER_MARKED_LNS_PROCESSED);
        nToBeCleanedLNsProcessed =
            new LongStat(stats, CLEANER_TO_BE_CLEANED_LNS_PROCESSED);
        nClusterLNsProcessed =
            new LongStat(stats, CLEANER_CLUSTER_LNS_PROCESSED);
        nPendingLNsLocked = new LongStat(stats, CLEANER_PENDING_LNS_LOCKED);
        nEntriesRead = new LongStat(stats, CLEANER_ENTRIES_READ);
        nDiskReads = new LongStat(stats, CLEANER_DISK_READS);
        nRepeatIteratorReads =
            new LongStat(stats, CLEANER_REPEAT_ITERATOR_READS);
        totalLogSize = new LongStat(stats, CLEANER_TOTAL_LOG_SIZE);
        currentMinUtilization = new IntStat(stats, CLEANER_MIN_UTILIZATION);
        currentMaxUtilization = new IntStat(stats, CLEANER_MAX_UTILIZATION);

        tracker = new UtilizationTracker(env, this);
        profile = new UtilizationProfile(env, tracker);
        expirationProfile = new ExpirationProfile(env);
        calculator = new UtilizationCalculator(env, this);
        fileSelector = new FileSelector();
        threads = new FileProcessor[0];
        protectedFileRanges = new LinkedList<>();
        logger = LoggerUtils.getLogger(getClass());
        totalRuns = new AtomicLong(0);

        /*
         * The trackDetail property is immutable because of the complexity (if
         * it were mutable) in determining whether to update the memory budget
         * and perform eviction.
         */
        trackDetail = env.getConfigManager().getBoolean
            (EnvironmentParams.CLEANER_TRACK_DETAIL);

        rmwFixEnabled = env.getConfigManager().getBoolean
            (EnvironmentParams.CLEANER_RMW_FIX);

        /* Initialize mutable properties and register for notifications. */
        setMutableProperties(env.getConfigManager());
        env.addConfigObserver(this);
    }

    /**
     * Process notifications of mutable property changes.
     *
     * @throws IllegalArgumentException via Environment ctor and
     * setMutableConfig.
     */
    public void envConfigUpdate(DbConfigManager cm,
                                EnvironmentMutableConfig ignore) {

        setMutableProperties(cm);

        /* A parameter that impacts cleaning may have changed. */
        wakeupActivate();
    }

    private void setMutableProperties(final DbConfigManager cm) {

        lockTimeout = cm.getDuration(EnvironmentParams.CLEANER_LOCK_TIMEOUT);

        readBufferSize = cm.getInt(EnvironmentParams.CLEANER_READ_SIZE);
        if (readBufferSize <= 0) {
            readBufferSize =
                cm.getInt(EnvironmentParams.LOG_ITERATOR_READ_SIZE);
        }

        lookAheadCacheSize =
            cm.getInt(EnvironmentParams.CLEANER_LOOK_AHEAD_CACHE_SIZE);

        nDeadlockRetries = cm.getInt(EnvironmentParams.CLEANER_DEADLOCK_RETRY);

        expunge = cm.getBoolean(EnvironmentParams.CLEANER_REMOVE);

        useDeletedDir =
            cm.getBoolean(EnvironmentParams.CLEANER_USE_DELETED_DIR);

        twoPassGap =
            cm.getInt(EnvironmentParams.CLEANER_TWO_PASS_GAP);

        twoPassThreshold =
            cm.getInt(EnvironmentParams.CLEANER_TWO_PASS_THRESHOLD);

        if (twoPassThreshold == 0) {
            twoPassThreshold =
                cm.getInt(EnvironmentParams.CLEANER_MIN_UTILIZATION) - 5;
        }

        gradualExpiration =
            cm.getBoolean(EnvironmentParams.CLEANER_GRADUAL_EXPIRATION);

        dbCacheClearCount =
            cm.getInt(EnvironmentParams.ENV_DB_CACHE_CLEAR_COUNT);

        int nThreads = cm.getInt(EnvironmentParams.CLEANER_THREADS);
        assert nThreads > 0;

        if (nThreads != threads.length) {

            /* Shutdown threads when reducing their number. */
            for (int i = nThreads; i < threads.length; i += 1) {
                if (threads[i] == null) {
                    continue;
                }
                threads[i].shutdown();
                threads[i] = null;
            }

            /* Copy existing threads that are still used. */
            FileProcessor[] newThreads = new FileProcessor[nThreads];
            for (int i = 0; i < nThreads && i < threads.length; i += 1) {
                newThreads[i] = threads[i];
            }

            /* Don't lose track of new threads if an exception occurs. */
            threads = newThreads;

            /* Start new threads when increasing their number. */
            for (int i = 0; i < nThreads; i += 1) {
                if (threads[i] != null) {
                    continue;
                }
                threads[i] = new FileProcessor(
                    name + '-' + (i + 1),
                    i == 0 /*firstThread*/,
                    env, this, profile, calculator, fileSelector);
            }
        }

        cleanerBytesInterval = cm.getLong(
            EnvironmentParams.CLEANER_BYTES_INTERVAL);

        if (cleanerBytesInterval == 0) {
            cleanerBytesInterval =
                cm.getLong(EnvironmentParams.LOG_FILE_MAX) / 4;
        }

        final int wakeupInterval =
            cm.getDuration(EnvironmentParams.CLEANER_WAKEUP_INTERVAL);

        for (FileProcessor thread : threads) {
            if (thread == null) {
                continue;
            }
            thread.setWaitTime(wakeupInterval);
        }

        fetchObsoleteSize =
            cm.getBoolean(EnvironmentParams.CLEANER_FETCH_OBSOLETE_SIZE);

        minAge = cm.getInt(EnvironmentParams.CLEANER_MIN_AGE);
        minUtilization = cm.getInt(EnvironmentParams.CLEANER_MIN_UTILIZATION);
        minFileUtilization =
            cm.getInt(EnvironmentParams.CLEANER_MIN_FILE_UTILIZATION);
    }

    public UtilizationTracker getUtilizationTracker() {
        return tracker;
    }

    public UtilizationProfile getUtilizationProfile() {
        return profile;
    }

    UtilizationCalculator getUtilizationCalculator() {
        return calculator;
    }

    public ExpirationProfile getExpirationProfile() {
        return expirationProfile;
    }

    public FileSelector getFileSelector() {
        return fileSelector;
    }

    public boolean getFetchObsoleteSize(DatabaseImpl db) {
        return fetchObsoleteSize && !db.isLNImmediatelyObsolete();
    }

    /**
     * @see EnvironmentParams#CLEANER_RMW_FIX
     * @see FileSummaryLN#postFetchInit
     */
    public boolean isRMWFixEnabled() {
        return rmwFixEnabled;
    }

    /* For unit testing only. */
    public void setFileChosenHook(TestHook hook) {
        fileChosenHook = hook;
    }

    /*
     * Delegate the run/pause/wakeup/shutdown DaemonRunner operations.  We
     * always check for null to account for the possibility of exceptions
     * during thread creation.  Cleaner daemon can't ever be run if No Locking
     * mode is enabled.
     */
    public void runOrPause(boolean run) {

        if (env.isNoLocking()) {
            return;
        }

        for (FileProcessor processor : threads) {
            if (processor == null) {
                continue;
            }
            if (run) {
                processor.activateOnWakeup();
            }
            processor.runOrPause(run);
        }
    }

    /**
     * If the number of bytes written since the last activation exceeds the
     * cleaner's byte interval, wakeup the file processor threads in activate
     * mode.
     */
    public void wakeupAfterWrite(int writeSize) {

        if (bytesWrittenSinceActivation.addAndGet(writeSize) >
            cleanerBytesInterval) {

            bytesWrittenSinceActivation.set(0);
            wakeupActivate();
        }
    }

    /**
     * Wakeup the file processor threads in activate mode, meaning that
     * FileProcessor.doClean will be called.
     *
     * @see FileProcessor#onWakeup()
     */
    public void wakeupActivate() {

        for (FileProcessor thread : threads) {
            if (thread == null) {
                continue;
            }
            thread.activateOnWakeup();
            thread.wakeup();
        }
    }

    public void requestShutdown() {
        for (FileProcessor thread : threads) {
            if (thread == null) {
                continue;
            }
            thread.requestShutdown();
        }
    }

    public void shutdown() {
        for (int i = 0; i < threads.length; i += 1) {
            if (threads[i] == null) {
                continue;
            }
            threads[i].shutdown();
            threads[i] = null;
        }
    }

    public int getNWakeupRequests() {
        int count = 0;
        for (FileProcessor thread : threads) {
            if (thread == null) {
                continue;
            }
            count += thread.getNWakeupRequests();
        }
        return count;
    }

    /**
     * Cleans selected files and returns the number of files cleaned.  This
     * method is not invoked by a deamon thread, it is programatically.
     *
     * @param cleanMultipleFiles is true to clean until we're under budget,
     * or false to clean at most one file.
     *
     * @param forceCleaning is true to clean even if we're not under the
     * utilization threshold.
     *
     * @return the number of files cleaned, not including files cleaned
     * unsuccessfully.
     */
    public int doClean(boolean cleanMultipleFiles, boolean forceCleaning) {

        FileProcessor processor = createProcessor();

        return processor.doClean
            (false /*invokedFromDaemon*/, cleanMultipleFiles, forceCleaning);
    }

    public FileProcessor createProcessor() {
        return new FileProcessor(
            "", false, env, this, profile, calculator, fileSelector);
    }

    /**
     * Load stats.
     */
    public StatGroup loadStats(StatsConfig config) {

        if (!config.getFast()) {
            totalLogSize.set(profile.getTotalLogSize());
        }

        currentMinUtilization.set(calculator.getCurrentMinUtilization());
        currentMaxUtilization.set(calculator.getCurrentMaxUtilization());

        StatGroup copyStats = stats.cloneGroup(config.getClear());
        /* Add the FileSelector's stats to the cleaner stat group. */
        copyStats.addAll(fileSelector.loadStats());

        return copyStats;
    }

    /**
     * Deletes all files that are safe-to-delete and which are not protected by
     * a DbBackup or replication. Files are deleted only if there are no
     * read-only processes.
     *
     * Log file deletion is coordinated by the use of three mechanisms:
     *
     * 1) To guard against read/only processes, the would-be deleter tries to
     * get an exclusive lock on the environment. This will not be possible if a
     * read/only process exists.  File locks must be used for inter-process
     * coordination. But note that file locks are not supported intra-process.
     *
     * 2) Synchronization on the protectedFileRanges field.  Elements are added
     * to and removed from the protectedFileRanges collection by DbBackup.
     * More than one backup may be occurring at once, hence a collection of
     * protectedFileRanges is maintained, and the files protected are the range
     * starting with the minimum value returned by the objects in that
     * collection.
     *
     * 3) In a replicated environment, files are protected from deletion by the
     * CBVLSN (CleanerBarrier VLSN). No file greater or equal to the CBVLSN
     * file may be deleted.
     *
     * For case (2) and (3), all coordinated activities -- replication, backup
     * and file deletion -- can only be carried out by a read-write process, so
     * we know that all activities are occurring in the same process because
     * there can only be one JE read-write process per environment.
     *
     * This method is synchronized to prevent multiple threads from requesting
     * the environment lock or deleting the same files.
     */
    synchronized void deleteSafeToDeleteFiles() {

        /* Fail loudly if the environment is invalid. */
        env.checkIfInvalid();

        /* Fail silently if the environment is not open. */
        if (env.mayNotWrite()) {
            return;
        }

        final NavigableSet<Long> safeToDeleteFiles =
            fileSelector.getSafeToDeleteFiles();

        if (safeToDeleteFiles.isEmpty()) {
            return; /* Nothing to do. */
        }

        final StringBuilder haMsg = new StringBuilder();

        /*
         * Ask HA to filter the "safe to delete" file set to determine which
         * are needed for HA purposes, and are protected.  We can safely assume
         * that if a file is declared to be unprotected by HA, and eligible to
         * delete, it will never be deemed on a later call to be protected.
         * This lets us avoid any synchronization between cleaning and HA.
         */
        NavigableSet<Long> unprotectedFiles =
            env.getUnprotectedFileSet(safeToDeleteFiles, haMsg);

        if (unprotectedFiles == null) {

            /*
             * The replicated node is not available, so the cleaner barrier can
             * not be read. Don't delete any files.
             */
            return;
        }

        if (unprotectedFiles.isEmpty()) {
            /* Leave a clue for analyzing log file deletion problems. */
            logProtectedFiles(
                "they are protected by replication", safeToDeleteFiles, haMsg);
            return; /* Nothing to do. */
        }

        /*
         * Truncate the entries in the VLSNIndex that reference VLSNs in the
         * files to be deleted.  [#16566]
         *
         * This is done prior to deleting the files to ensure that the
         * replicator removes the files from the VLSNIndex.  If we were to
         * truncate after deleting a file, we may crash before the truncation
         * and would have to "replay" the truncation later in
         * UtilizationProfile.populateCache.  This would be more complex and
         * the lastVLSN for the files would not be available.
         *
         * OTOH, if we crash after the truncation and before deleting a file,
         * it is very likely that we will re-clean the zero utilization file
         * and delete it later.  This will only cause a redundant truncation.
         *
         * This is done before locking the environment to minimize the interval
         * during which the environment is locked and read-only processes are
         * blocked.  We may unnecessarily truncate the VLSNIndex if we can't
         * lock the environment, but that is a lesser priority.
         *
         * We intentionally do not honor the protected file ranges specified by
         * DbBackups when truncating, because the VLSNIndex is protected only
         * by the CBVLSN.  Luckily, this also means we do not need to
         * synchronize on protectedFileRanges while truncating, and DbBackups
         * will not be blocked by this potentially expensive operation.
         */
        Long[] unprotectedFilesArray = unprotectedFiles.toArray(
            new Long[unprotectedFiles.size()]);

        for (int i = unprotectedFilesArray.length - 1; i >= 0; i -= 1) {
            Long fileNum = unprotectedFilesArray[i];

            /*
             * Truncate VLSNIndex for the highest numbered file with a VLSN. We
             * search from high to low because some files may not contain a
             * VLSN. If the truncate does have to do work, the VLSNIndex will
             * ensure that the change is fsynced to disk. [#20702]
             */
            VLSN lastVlsn = fileSelector.getLastVLSN(fileNum);
            if ((lastVlsn != null) && !lastVlsn.isNull()) {
                env.vlsnHeadTruncate(lastVlsn, fileNum);
                break;
            }
        }

        /*
         * If we can't get an exclusive lock, then there are other processes
         * with the environment open read-only and we can't delete any files.
         */
        final FileManager fileManager = env.getFileManager();
        if (!fileManager.lockEnvironment(false, true)) {
            logProtectedFiles(
                "of read-only processes", safeToDeleteFiles, haMsg);
            return;
        }

        /* Be sure to release the environment lock in the finally block. */
        try {
            /* Synchronize while deleting files to block DbBackup.start. */
            synchronized (protectedFileRanges) {

                /* Intersect the protected ranges for active DbBackups. */
                if (!protectedFileRanges.isEmpty()) {
                    final Long minRangeStart =
                        Collections.min(protectedFileRanges);
                    if (minRangeStart <= unprotectedFiles.first()) {
                        /*
                         * Simply return, no files can be deleted. Other
                         * threads may asynchronously change the
                         * protectedFileRanges list as a way of temporarily
                         * imposing a curb on cleaning. For example,
                         * DiskOrderedScan and DbBackup can increase the
                         * protectedFileRange to protect a scan or backup, and
                         * there is no restriction on how much the range can be
                         * increased. While the calculation of the
                         * unprotectedFiles Set considers the
                         * protectedFileRange, check the protectedFileRange
                         * again while under synchronization to see if there
                         * have been any asynchronous changes since that
                         * calculation, which might make have extended the
                         * range and made it larger than the unprotectedFile
                         * set.
                         */
                        return;
                    }
                    unprotectedFiles =
                        unprotectedFiles.headSet(minRangeStart, false);
                }

                /* Delete the unprotected files. */
                for (final Iterator<Long> iter = unprotectedFiles.iterator();
                     iter.hasNext();) {
                    final Long fileNum = iter.next();
                    final boolean deleted;
                    final String expungeLabel = expunge ? "delete" : "rename";
                    final String expungedLabel = expungeLabel + "d";
                    try {
                        if (expunge) {
                            deleted = fileManager.deleteFile(fileNum);
                        } else {
                            final File newFile = fileManager.renameFile(
                                fileNum, FileManager.DEL_SUFFIX,
                                useDeletedDir ? DELETED_SUBDIR : null);

                            if (newFile != null) {
                                newFile.setLastModified(
                                    System.currentTimeMillis());
                            }

                            deleted = (newFile != null);
                        }
                    } catch (IOException e) {
                        throw new EnvironmentFailureException
                            (env, EnvironmentFailureReason.LOG_WRITE,
                             "Unable to " + expungeLabel + " " + fileNum, e);
                    }
                    if (deleted) {

                        /*
                         * Deletion was successful.  Log a trace message for
                         * debugging of log cleaning behavior.
                         */
                        LoggerUtils.traceAndLog(logger, env, Level.FINE,
                                                "Cleaner deleted file 0x" +
                                                Long.toHexString(fileNum));
                    } else if (!fileManager.isFileValid(fileNum)) {

                        /*
                         * Somehow the file was previously deleted.  This could
                         * indicate an internal state error, and therefore we
                         * output a trace message.  But we should not
                         * repeatedly attempt to delete it, so we do remove it
                         * from the profile below.
                         */
                        LoggerUtils.traceAndLog
                            (logger, env, Level.SEVERE,
                             "Cleaner deleteSafeToDeleteFiles Log file 0x" +
                             Long.toHexString(fileNum) + " was previously " +
                             expungedLabel + ".  State: " + fileSelector );
                    } else {

                        /*
                         * We will retry the deletion later if file still
                         * exists.  The deletion could have failed on Windows
                         * if the file was recently closed.  Remove the file
                         * from unprotectedFiles. That way, we won't remove it
                         * from the FileSelector's safe-to-delete set or the UP
                         * below, and we will retry the file deletion later.
                         */
                        iter.remove();

                        LoggerUtils.traceAndLog
                            (logger, env, Level.WARNING,
                             "Cleaner deleteSafeToDeleteFiles Log file 0x" +
                             Long.toHexString(fileNum) + " could not be " +
                             expungedLabel + ". This operation will be " +
                             "retried at the next checkpoint. State: " +
                             fileSelector);
                    }
                }
            }
        } finally {
            fileManager.releaseExclusiveLock();
        }

        /*
         * Now unprotectedFiles contains only the files we deleted above.  We
         * can update the UP (and FileSelector) here outside of the
         * synchronization block and without the environment locked.  That way,
         * DbBackups and read-only processes will not be blocked by the
         * expensive UP operation.
         *
         * We do not retry if an error occurs deleting the UP database entries
         * below.  Retrying (when file deletion fails) is intended only to
         * solve a problem on Windows where deleting a log file isn't always
         * possible immediately after closing it.
         *
         * Remove the file from the UP before removing it from the
         * FileSelector's safe-to-delete set.  If we remove in the reverse
         * order, it may be selected for cleaning.  Always remove the file from
         * the safe-to-delete set (in a finally block) so that we don't attempt
         * to delete the file again.
         */
        profile.removePerDbMetadata(
            unprotectedFiles,
            fileSelector.getCleanedDatabases(unprotectedFiles));

        for (Long fileNum : unprotectedFiles) {
            try {
                profile.removePerFileMetadata(fileNum);
                expirationProfile.removeFile(fileNum);
            } finally {
                fileSelector.removeDeletedFile(fileNum, env.getMemoryBudget());
            }
            nCleanerDeletions.increment();
        }

        /* Leave a clue for analyzing log file deletion problems. */
        if (safeToDeleteFiles.size() > unprotectedFiles.size()) {
            final List<Long> filesNotDeleted = new ArrayList<>(
                safeToDeleteFiles.size() - unprotectedFiles.size());
            for (final Long file : safeToDeleteFiles) {
                if (!unprotectedFiles.contains(file)) {
                    filesNotDeleted.add(file);
                }
            }
            logProtectedFiles(
                "they are protected by DbBackup or replication",
                new TreeSet<>(filesNotDeleted), haMsg);
        }
    }

    /**
     * Log a message describing files that were not deleted because they were
     * protected.
     */
    private void logProtectedFiles(
        final String reason,
        final SortedSet<Long> filesNotDeleted,
        final StringBuilder haMsg) {

        final String msg = "Cleaner has " + filesNotDeleted.size() +
            " files not deleted because " + reason +
            ": " + FormatUtil.asString(filesNotDeleted) +
            haMsg;

        if (!filesNotDeleted.equals(lastProtectedFileSet)) {

            LoggerUtils.logMsg(logger, env, Level.INFO, msg);

        } else if (!msg.equals(lastProtectedFileMsg)) {

            LoggerUtils.logMsg(logger, env, Level.FINE, msg);
        }

        /* Don't keep repeating the same message. */
        lastProtectedFileSet = filesNotDeleted;
        lastProtectedFileMsg = msg;
    }

    /**
     * Adds a range of log files to be protected from deletion during a backup
     * or similar procedures where log files must not be deleted.
     *
     * <p>This method is called automatically by the {@link
     * com.sleepycat.je.util.DbBackup} utility and is provided here as a
     * separate API for advanced applications that may implement a custom
     * backup procedure.</p>
     *
     * <p><em>WARNING:</em> After calling this method, deletion of log files in
     * the file range by the JE log cleaner will be disabled until {@link
     * #removeProtectedFileRange} is called.  To prevent unbounded growth of
     * disk usage, be sure to call {@link #removeProtectedFileRange} to
     * re-enable log file deletion.</p>
     *
     * @param firstProtectedFile the number of the first file to be protected.
     * The protected range is from this file number to the last (highest
     * numbered) file in the log.
     *
     * @since 4.0
     */
    public void addProtectedFileRange(long firstProtectedFile) {
        synchronized (protectedFileRanges) {
            protectedFileRanges.add(firstProtectedFile);
        }
    }

    /**
     * Removes a range of log files to be protected after calling {@link
     * #addProtectedFileRange}.
     *
     * @param firstProtectedFile the value previously passed to {@link
     * #addProtectedFileRange}.
     *
     * @throws EnvironmentFailureException if {@code firstProtectedFile} is not
     * currently the start of a protected range.
     *
     * @since 4.0
     */
    public void removeProtectedFileRange(long firstProtectedFile) {
        synchronized (protectedFileRanges) {
            if (!protectedFileRanges.remove(firstProtectedFile)) {
                throw EnvironmentFailureException.unexpectedState
                    ("File range starting with 0x" +
                     Long.toHexString(firstProtectedFile) +
                     " is not currently protected");
            }
        }
    }

    /**
     * Returns a copy of the cleaned and processed files at the time a
     * checkpoint starts.
     *
     * <p>If non-null is returned, the checkpoint should flush an extra level,
     * and addCheckpointedFiles() should be called when the checkpoint is
     * complete.</p>
     */
    public CheckpointStartCleanerState getFilesAtCheckpointStart() {

        /* Pending LNs can prevent file deletion. */
        processPending();

        return fileSelector.getFilesAtCheckpointStart();
    }

    /**
     * When a checkpoint is complete, update the files that were returned at
     * the beginning of the checkpoint.
     */
    public void updateFilesAtCheckpointEnd(CheckpointStartCleanerState info) {
        fileSelector.updateFilesAtCheckpointEnd(info);
        deleteSafeToDeleteFiles();

        /*
         * Periodically process completed expiration trackers. This is done
         * here in case cleaner threads are disabled.
         */
        expirationProfile.processCompletedTrackers();
    }

    /**
     * If any LNs or databases are pending, process them.  This method should
     * be called often enough to prevent the pending LN set from growing too
     * large.
     */
    void processPending() {

        /*
         * This method is not synchronized because that would block cleaner
         * and checkpointer threads unnecessarily.  However, we do prevent
         * reentrancy, for two reasons:
         * 1. It is wasteful for two threads to process the same pending
         *    entries.
         * 2. Many threads calling getDb may increase the liklihood of
         *    livelock. [#20816]
         */
        if (!processPendingReentrancyGuard.compareAndSet(false, true)) {
            return;
        }

        try {
            final DbTree dbMapTree = env.getDbTree();

            final LockManager lockManager =
                env.getTxnManager().getLockManager();

            final Map<Long, LNInfo> pendingLNs = fileSelector.getPendingLNs();

            if (pendingLNs != null) {
                final TreeLocation location = new TreeLocation();

                for (final Map.Entry<Long, LNInfo> entry :
                     pendingLNs.entrySet()) {

                    final long logLsn = entry.getKey();
                    final LNInfo info = entry.getValue();

                    if (env.expiresWithin(
                        info.getExpirationTime(),
                        0 - env.getTtlLnPurgeDelay())) {

                        if (lockManager.isLockUncontended(logLsn)) {
                            fileSelector.removePendingLN(logLsn);
                            nLNsExpired.increment();
                            nLNsObsolete.increment();
                        } else {
                            nPendingLNsLocked.increment();
                        }
                        continue;
                    }

                    final DatabaseId dbId = info.getDbId();
                    final DatabaseImpl db = dbMapTree.getDb(dbId, lockTimeout);

                    try {
                        final byte[] key = info.getKey();

                        /* Evict before processing each entry. */
                        if (DO_CRITICAL_EVICTION) {
                            env.daemonEviction(true /*backgroundIO*/);
                        }

                        processPendingLN(logLsn, db, key, location);

                    } finally {
                        dbMapTree.releaseDb(db);
                    }
                }
            }

            final DatabaseId[] pendingDBs = fileSelector.getPendingDBs();
            if (pendingDBs != null) {
                for (final DatabaseId dbId : pendingDBs) {
                    final DatabaseImpl db = dbMapTree.getDb(dbId, lockTimeout);
                    try {
                        if (db == null || db.isDeleteFinished()) {
                            fileSelector.removePendingDB(dbId);
                        }
                    } finally {
                        dbMapTree.releaseDb(db);
                    }
                }
            }
        } finally {
            processPendingReentrancyGuard.set(false);
        }
    }

    /**
     * Processes a pending LN, getting the lock first to ensure that the
     * overhead of retries is minimal.
     */
    private void processPendingLN(
        final long logLsn,
        final DatabaseImpl db,
        final byte[] keyFromLog,
        final TreeLocation location) {

        boolean parentFound;          // We found the parent BIN.
        boolean processedHere = true; // The LN was cleaned here.
        boolean lockDenied = false;   // The LN lock was denied.
        boolean obsolete = false;     // The LN is no longer in use.
        boolean completed = false;    // This method completed.

        BasicLocker locker = null;
        BIN bin = null;

        try {
            nPendingLNsProcessed.increment();

            /*
             * If the DB is gone, this LN is obsolete.  If delete cleanup is in
             * progress, put the DB into the DB pending set; this LN will be
             * declared deleted after the delete cleanup is finished.
             */
            if (db == null || db.isDeleted()) {
                addPendingDB(db);
                nLNsDead.increment();
                obsolete = true;
                completed = true;
                return;
            }

            final Tree tree = db.getTree();
            assert tree != null;

            /*
             * Get a non-blocking read lock on the original log LSN.  If this
             * fails, then the original LSN is still write-locked.  We may have
             * to lock again, if the LSN has changed in the BIN, but this
             * initial check prevents a Btree lookup in some cases.
             */
            locker = BasicLocker.createBasicLocker(env, false /*noWait*/);

            /* Don't allow this short-lived lock to be preempted/stolen. */
            locker.setPreemptable(false);

            final LockResult lockRet = locker.nonBlockingLock(
                logLsn, LockType.READ, false /*jumpAheadOfWaiters*/, db);

            if (lockRet.getLockGrant() == LockGrantType.DENIED) {
                /* Try again later. */
                nPendingLNsLocked.increment();
                lockDenied = true;
                completed = true;
                return;
            }

            /*
             * Search down to the bottom most level for the parent of this LN.
             */
            parentFound = tree.getParentBINForChildLN(
                location, keyFromLog, false /*splitsAllowed*/,
                false /*blindDeltaOps*/, UPDATE_GENERATION);

            bin = location.bin;
            final int index = location.index;

            if (!parentFound) {
                nLNsDead.increment();
                obsolete = true;
                completed = true;
                return;
            }

            /* Migrate an LN. */
            processedHere = false;

            migratePendingLN(db, logLsn, bin.getLsn(index), bin, index);

            completed = true;

        } catch (RuntimeException e) {
            e.printStackTrace();
            LoggerUtils.traceAndLogException(
                env, "com.sleepycat.je.cleaner.Cleaner",
                "processLN", "Exception thrown: ", e);
            throw e;
        } finally {
            if (bin != null) {
                bin.releaseLatch();
            }

            if (locker != null) {
                locker.operationEnd();
            }

            /*
             * If migratePendingLN was not called above, remove the pending LN
             * and perform tracing in this method.
             */
            if (processedHere) {
                if (completed && !lockDenied) {
                    fileSelector.removePendingLN(logLsn);
                }
                logFine(CLEAN_PENDING_LN, null /*node*/, DbLsn.NULL_LSN,
                        completed, obsolete, false /*migrated*/);
            }
        }
    }

    /**
     * Migrate a pending LN in the given BIN entry, if it is not obsolete.  The
     * BIN must be latched on entry and is left latched by this method.
     */
    private void migratePendingLN(
        final DatabaseImpl db,
        final long logLsn,
        final long treeLsn,
        final BIN bin,
        final int index) {

        /* Status variables are used to generate debug tracing info. */
        boolean obsolete = false;    // The LN is no longer in use.
        boolean migrated = false;    // The LN was in use and is migrated.
        boolean lockDenied = false;  // The LN lock was denied.
        boolean completed = false;   // This method completed.
        boolean clearTarget = false; // Node was non-resident when called.

        /*
         * If wasCleaned is false we don't count statistics unless we migrate
         * the LN.  This avoids double counting.
         */
        BasicLocker locker = null;
        LN ln = null;

        try {
            if (treeLsn == DbLsn.NULL_LSN) {
                /* This node was never written, no need to migrate. */
                completed = true;
                return;
            }

            /* If the record has been deleted, the logrec is obsolete */
            if (bin.isEntryKnownDeleted(index)) {
                nLNsDead.increment();
                obsolete = true;
                completed = true;
                return;
            }

            /*
             * Get a non-blocking read lock on the LN.  A pending node is
             * already locked, but the original pending LSN may have changed.
             * We must lock the current LSN to guard against aborts.
             */
            if (logLsn != treeLsn) {

                locker = BasicLocker.createBasicLocker(env, false /*noWait*/);
                /* Don't allow this short-lived lock to be preempted/stolen. */
                locker.setPreemptable(false);

                final LockResult lockRet = locker.nonBlockingLock(
                    treeLsn, LockType.READ, false /*jumpAheadOfWaiters*/, db);

                if (lockRet.getLockGrant() == LockGrantType.DENIED) {

                    /*
                     * LN is currently locked by another Locker, so we can't
                     * assume anything about the value of the LSN in the bin.
                     */
                    nLNsLocked.increment();
                    lockDenied = true;
                    completed = true;
                    return;
                } else {
                    nLNsDead.increment();
                    obsolete = true;
                    completed = true;
                    return;
                }

            } else if (bin.isEmbeddedLN(index)) {
                throw EnvironmentFailureException.unexpectedState(
                    env,
                    "LN is embedded although its associated logrec (at " +
                    treeLsn + " does not have the embedded flag on");
            }

            /*
             * Get the ln so that we can log it to its new position.
             * Notice that the fetchLN() call below will return null if the
             * slot is defunct and the LN has been purged by the cleaner.
             */
            ln = (LN) bin.getTarget(index);
            if (ln == null) {
                ln = bin.fetchLN(index, CacheMode.EVICT_LN);
                clearTarget = !db.getId().equals(DbTree.ID_DB_ID);
            }

            /* Don't migrate defunct LNs. */
            if (ln == null || ln.isDeleted()) {
                bin.setKnownDeletedAndEvictLN(index);
                nLNsDead.increment();
                obsolete = true;
                completed = true;
                return;
            }

            /*
             * Migrate the LN.
             *
             * Do not pass a locker, because there is no need to lock the new
             * LSN, as done for user operations.  Another locker cannot attempt
             * to lock the new LSN until we're done, because we release the
             * lock before we release the BIN latch.
             */
            final LogItem logItem = ln.log(
                env, db, null /*locker*/, null /*writeLockInfo*/,
                false /*newEmbeddedLN*/, bin.getKey(index),
                bin.getExpiration(index), bin.isExpirationInHours(),
                false /*currEmbeddedLN*/, treeLsn, bin.getLastLoggedSize(index),
                false /*isInsertion*/, true /*backgroundIO*/,
                getMigrationRepContext(ln));

            bin.updateEntry(
                index, logItem.lsn, ln.getVLSNSequence(),
                logItem.size);

            nLNsMigrated.increment();

            /* Lock new LSN on behalf of existing lockers. */
            CursorImpl.lockAfterLsnChange(
                db, treeLsn, logItem.lsn, null /*excludeLocker*/);

            migrated = true;
            completed = true;

        } finally {
            if (completed && !lockDenied) {
                fileSelector.removePendingLN(logLsn);
            }

            /*
             * If the node was originally non-resident, evict it now so that we
             * don't create more work for the evictor and reduce the cache
             * memory available to the application.
             */
            if (clearTarget) {
                bin.evictLN(index);
            }

            if (locker != null) {
                locker.operationEnd();
            }

            logFine(
                CLEAN_PENDING_LN, ln, treeLsn, completed, obsolete, migrated);
        }
    }

    /**
     * Returns the ReplicationContext to use for migrating the given LN.  If
     * VLSNs are preserved in this Environment then the VLSN is logically part
     * of the data record, and LN.getVLSNSequence will return the VLSN, which
     * should be included in the migrated LN.
     */
    static ReplicationContext getMigrationRepContext(LN ln) {
        long vlsnSeq = ln.getVLSNSequence();
        if (vlsnSeq <= 0) {
            return ReplicationContext.NO_REPLICATE;
        }
        return new ReplicationContext(new VLSN(vlsnSeq),
                                      false /*inReplicationStream*/);
    }

    /**
     * Adds the DB ID to the pending DB set if it is being deleted but deletion
     * is not yet complete.
     */
    void addPendingDB(DatabaseImpl db) {
        if (db != null && db.isDeleted() && !db.isDeleteFinished()) {
            DatabaseId id = db.getId();
            if (fileSelector.addPendingDB(id)) {
                LoggerUtils.logMsg(logger, env, Level.FINE,
                                   "CleanAddPendingDB " + id);
            }
        }
    }

    /**
     * Send trace messages to the java.util.logger. Don't rely on the logger
     * alone to conditionalize whether we send this message, we don't even want
     * to construct the message if the level is not enabled.
     */
    void logFine(String action,
               Node node,
               long logLsn,
               boolean completed,
               boolean obsolete,
               boolean dirtiedMigrated) {

        if (logger.isLoggable(Level.FINE)) {
            StringBuilder sb = new StringBuilder();
            sb.append(action);
            if (node instanceof IN) {
                sb.append(" node=");
                sb.append(((IN) node).getNodeId());
            }
            sb.append(" logLsn=");
            sb.append(DbLsn.getNoFormatString(logLsn));
            sb.append(" complete=").append(completed);
            sb.append(" obsolete=").append(obsolete);
            sb.append(" dirtiedOrMigrated=").append(dirtiedMigrated);

            LoggerUtils.logMsg(logger, env, Level.FINE, sb.toString());
        }
    }

    /**
     * Release resources and update memory budget. Should only be called
     * when this environment is closed and will never be accessed again.
     */
    public void close() {
        profile.close();
        tracker.close();
        fileSelector.close(env.getMemoryBudget());
    }
}
