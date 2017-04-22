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

package com.sleepycat.je.txn;

import com.sleepycat.je.utilint.StatDefinition;
import com.sleepycat.je.utilint.StatDefinition.StatType;

/**
 * Per-stat Metadata for JE lock statistics.
 */
public class LockStatDefinition {

    public static final String GROUP_NAME = "Locks";
    public static final String GROUP_DESC = 
        "Locks held by data operations, latching contention on lock table.";

    public static final StatDefinition LOCK_READ_LOCKS =
        new StatDefinition("nReadLocks",
                           "Number of read locks currently held.",
                           StatType.CUMULATIVE);

    public static final StatDefinition LOCK_WRITE_LOCKS =
        new StatDefinition("nWriteLocks",
                           "Number of write locks currently held.",
                           StatType.CUMULATIVE);

    public static final StatDefinition LOCK_OWNERS =
        new StatDefinition("nOwners",
                           "Number of lock owners in lock table.",
                           StatType.CUMULATIVE);

    public static final StatDefinition LOCK_REQUESTS =
        new StatDefinition("nRequests",
                           "Number of times a lock request was made.");

    public static final StatDefinition LOCK_TOTAL =
        new StatDefinition("nTotalLocks",
                           "Number of locks current in lock table.",
                           StatType.CUMULATIVE);

    public static final StatDefinition LOCK_WAITS =
        new StatDefinition("nWaits",
                           "Number of times a lock request blocked.");

    public static final StatDefinition LOCK_WAITERS =
        new StatDefinition("nWaiters",
                           "Number of transactions waiting for a lock.",
                           StatType.CUMULATIVE);
}
