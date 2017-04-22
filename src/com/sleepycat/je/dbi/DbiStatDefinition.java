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

package com.sleepycat.je.dbi;

import com.sleepycat.je.utilint.StatDefinition;
import com.sleepycat.je.utilint.StatDefinition.StatType;

/**
 * Per-stat Metadata for JE EnvironmentImpl and MemoryBudget statistics.
 */
public class DbiStatDefinition {

    public static final String MB_GROUP_NAME = "Cache Layout";
    public static final String MB_GROUP_DESC =
        "Allocation of resources in the cache.";

    public static final String ENV_GROUP_NAME = "Environment";
    public static final String ENV_GROUP_DESC =
        "General environment wide statistics.";

    public static final String THROUGHPUT_GROUP_NAME = "Op";
    public static final String THROUGHPUT_GROUP_DESC =
        "Thoughput statistics for JE calls.";

    /* The following stat definitions are used in MemoryBudget. */
    public static final StatDefinition MB_SHARED_CACHE_TOTAL_BYTES =
        new StatDefinition("sharedCacheTotalBytes",
                           "Total amount of the shared JE cache in use, in " +
                           "bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_TOTAL_BYTES =
        new StatDefinition("cacheTotalBytes",
                           "Total amount of JE cache in use, in bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_DATA_BYTES =
        new StatDefinition("dataBytes",
                           "Amount of JE cache used for holding data, keys " +
                           "and internal Btree nodes, in bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_DATA_ADMIN_BYTES =
        new StatDefinition("dataAdminBytes",
                           "Amount of JE cache used for holding database " +
                           "metadata, in bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_DOS_BYTES =
        new StatDefinition("DOSBytes",
                           "Amount of JE cache consumed by " +
                           "disk-ordered scans, in bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_ADMIN_BYTES =
        new StatDefinition("adminBytes",
                           "Number of bytes of JE cache used for log " +
                           "cleaning metadata and other administrative " +
                           "structure, in bytes.",
                           StatType.CUMULATIVE);

    public static final StatDefinition MB_LOCK_BYTES =
        new StatDefinition("lockBytes",
                           "Number of bytes of JE cache used for holding " +
                           "locks and transactions, in bytes.",
                           StatType.CUMULATIVE);

    /* The following stat definitions are used in EnvironmentImpl. */
    public static final StatDefinition ENV_RELATCHES_REQUIRED =
        new StatDefinition("btreeRelatchesRequired",
                           "Returns the number of btree latch upgrades " +
                           "required while operating on this " +
                           "Environment. A measurement of contention.");

    public static final StatDefinition ENV_CREATION_TIME =
        new StatDefinition("environmentCreationTime",
                           "Returns the time the Environment " +
                           "was created. ",
                           StatType.CUMULATIVE);

    public static final StatDefinition ENV_BIN_DELTA_GETS =
        new StatDefinition(
            "nBinDeltaGet",
            "The number of gets performed in BIN deltas");

    public static final StatDefinition ENV_BIN_DELTA_INSERTS =
        new StatDefinition(
            "nBinDeltaInsert",
            "The number of insertions performed in BIN deltas");

    public static final StatDefinition ENV_BIN_DELTA_UPDATES =
        new StatDefinition(
            "nBinDeltaUpdate",
            "The number of updates performed in BIN deltas");

    public static final StatDefinition ENV_BIN_DELTA_DELETES =
        new StatDefinition(
            "nBinDeltaDelete",
            "The number of deletions performed in BIN deltas");

    /* The following stat definitions are used for throughput. */

    public static final StatDefinition THROUGHPUT_PRI_SEARCH =
        new StatDefinition(
            "priSearch",
            "Number of successful primary DB key search operations.");

    public static final StatDefinition THROUGHPUT_PRI_SEARCH_FAIL =
        new StatDefinition(
            "priSearchFail",
            "Number of failed primary DB key search operations.");

    public static final StatDefinition THROUGHPUT_SEC_SEARCH =
        new StatDefinition(
            "secSearch",
            "Number of successful secondary DB key search operations.");

    public static final StatDefinition THROUGHPUT_SEC_SEARCH_FAIL =
        new StatDefinition(
            "secSearchFail",
            "Number of failed secondary DB key search operations.");

    public static final StatDefinition THROUGHPUT_PRI_POSITION =
        new StatDefinition(
            "priPosition",
            "Number of successful primary DB position operations.");

    public static final StatDefinition THROUGHPUT_SEC_POSITION =
        new StatDefinition(
            "secPosition",
            "Number of successful secondary DB position operations.");

    public static final StatDefinition THROUGHPUT_PRI_INSERT =
        new StatDefinition(
            "priInsert",
            "Number of successful primary DB insertion operations.");

    public static final StatDefinition THROUGHPUT_PRI_INSERT_FAIL =
        new StatDefinition(
            "priInsertFail",
            "Number of failed primary DB insertion operations.");

    public static final StatDefinition THROUGHPUT_SEC_INSERT =
        new StatDefinition(
            "secInsert",
            "Number of successful secondary DB insertion operations.");

    public static final StatDefinition THROUGHPUT_PRI_UPDATE =
        new StatDefinition(
            "priUpdate",
            "Number of successful primary DB update operations.");

    public static final StatDefinition THROUGHPUT_SEC_UPDATE =
        new StatDefinition(
            "secUpdate",
            "Number of successful secondary DB update operations.");

    public static final StatDefinition THROUGHPUT_PRI_DELETE =
        new StatDefinition(
            "priDelete",
            "Number of successful primary DB deletion operations.");

    public static final StatDefinition THROUGHPUT_PRI_DELETE_FAIL =
        new StatDefinition(
            "priDeleteFail",
            "Number of failed primary DB deletion operations.");

    public static final StatDefinition THROUGHPUT_SEC_DELETE =
        new StatDefinition(
            "secDelete",
            "Number of successful secondary DB deletion operations.");
}
