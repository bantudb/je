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

package com.sleepycat.je.recovery;

import com.sleepycat.je.utilint.StatDefinition;
import com.sleepycat.je.utilint.StatDefinition.StatType;

/**
 * Per-stat Metadata for JE checkpointer statistics.
 */
public class CheckpointStatDefinition {
    public static final String GROUP_NAME = "Checkpoints";
    public static final String GROUP_DESC =
        "Frequency and extent of checkpointing activity.";

    public static final StatDefinition CKPT_CHECKPOINTS =
        new StatDefinition("nCheckpoints",
                           "Total number of checkpoints run so far.");

    public static final StatDefinition CKPT_LAST_CKPTID =
        new StatDefinition("lastCheckpointId",
                           "Id of the last checkpoint.",
                           StatType.CUMULATIVE);

    public static final StatDefinition CKPT_FULL_IN_FLUSH =
        new StatDefinition("nFullINFlush",
                           "Accumulated number of full INs flushed to the "+
                           "log.");

    public static final StatDefinition CKPT_FULL_BIN_FLUSH =
        new StatDefinition("nFullBINFlush",
                           "Accumulated number of full BINs flushed to the " +
                           "log.");

    public static final StatDefinition CKPT_DELTA_IN_FLUSH =
        new StatDefinition("nDeltaINFlush",
                           "Accumulated number of Delta INs flushed to the " +
                           "log.");

    public static final StatDefinition CKPT_LAST_CKPT_INTERVAL =
        new StatDefinition("lastCheckpointInterval",
                           "Byte length from last checkpoint start to the " +
                           "previous checkpoint start.",
                           StatType.CUMULATIVE);

    public static final StatDefinition CKPT_LAST_CKPT_START =
        new StatDefinition("lastCheckpointStart",
                           "Location in the log of the last checkpoint start.",
                           StatType.CUMULATIVE);

    public static final StatDefinition CKPT_LAST_CKPT_END =
        new StatDefinition("lastCheckpointEnd",
                           "Location in the log of the last checkpoint end.",
                           StatType.CUMULATIVE);
}
