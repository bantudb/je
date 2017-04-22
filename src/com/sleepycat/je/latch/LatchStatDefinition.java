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

package com.sleepycat.je.latch;

import com.sleepycat.je.utilint.StatDefinition;

/**
 * Per-stat Metadata for JE latch statistics.
 */
public class LatchStatDefinition {

    public static final String GROUP_NAME = "Latch";
    public static final String GROUP_DESC = "Latch characteristics";

    public static final StatDefinition LATCH_NO_WAITERS =
        new StatDefinition("nLatchAcquiresNoWaiters",
                           "Number of times the latch was acquired without " +
                           "contention.");

    public static final StatDefinition LATCH_SELF_OWNED =
        new StatDefinition("nLatchAcquiresSelfOwned",
                           "Number of times the latch was acquired it " +
                           "was already owned by the caller.");

    public static final StatDefinition LATCH_CONTENTION =
        new StatDefinition("nLatchAcquiresWithContention",
                           "Number of times the latch was acquired when it " +
                           "was already owned by another thread.");

    public static final StatDefinition LATCH_NOWAIT_SUCCESS =
        new StatDefinition("nLatchAcquiresNoWaitSuccessful",
                           "Number of successful no-wait acquires of " +
                           "the lock table latch.");

    public static final StatDefinition LATCH_NOWAIT_UNSUCCESS =
        new StatDefinition("nLatchAcquireNoWaitUnsuccessful",
                           "Number of unsuccessful no-wait acquires of " +
                           "the lock table latch.");

    public static final StatDefinition LATCH_RELEASES =
        new StatDefinition("nLatchReleases", "Number of latch releases.");
}
