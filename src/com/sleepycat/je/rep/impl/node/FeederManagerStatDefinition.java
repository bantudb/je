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

import static com.sleepycat.je.utilint.StatDefinition.StatType.CUMULATIVE;

import com.sleepycat.je.utilint.StatDefinition;

/**
 * Per-stat Metadata for HA Replay statistics.
 */
public class FeederManagerStatDefinition {

    public static final String GROUP_NAME = "FeederManager";
    public static final String GROUP_DESC =
        "A feeder is a replication stream connection between a master and " +
        "replica nodes.";

    public static StatDefinition N_FEEDERS_CREATED =
        new StatDefinition("nFeedersCreated",
                           "Number of Feeder threads since this node was " +
                           "started.");

    public static StatDefinition N_FEEDERS_SHUTDOWN =
        new StatDefinition("nFeedersShutdown",
                           "Number of Feeder threads that were shut down, " +
                           "either because this node, or the Replica " +
                           "terminated the connection.");

    public static StatDefinition N_MAX_REPLICA_LAG =
        new StatDefinition("nMaxReplicaLag",
                           "The maximum number of VLSNs by which a replica " +
                           "is lagging.");

    public static StatDefinition N_MAX_REPLICA_LAG_NAME =
        new StatDefinition("nMaxReplicaLagName",
                           "The name of the replica with the maximal lag.");

    public static StatDefinition REPLICA_DELAY_MAP =
        new StatDefinition("replicaDelayMap",
                           "A map from replica node name to the delay, in" +
                           " milliseconds, between when a transaction was" +
                           " committed on the master and when the master" +
                           " learned that the change was processed on the" +
                           " replica, if known. Returns an empty map if this" +
                           " node is not the master.",
                           CUMULATIVE);

    public static StatDefinition REPLICA_LAST_COMMIT_TIMESTAMP_MAP =
        new StatDefinition("replicaLastCommitTimestampMap",
                           "A map from replica node name to the commit" +
                           " timestamp of the last committed transaction" +
                           " that was processed on the replica, if known." +
                           " Returns an empty map if this node is not the" +
                           " master.",
                           CUMULATIVE);

    public static StatDefinition REPLICA_LAST_COMMIT_VLSN_MAP =
        new StatDefinition("replicaLastCommitVLSNMap",
                           "A map from replica node name to the VLSN of the" +
                           " last committed transaction that was processed" +
                           " on the replica, if known. Returns an empty map" +
                           " if this node is not the master.",
                           CUMULATIVE);

    public static StatDefinition REPLICA_VLSN_LAG_MAP =
        new StatDefinition("replicaVLSNLagMap",
                           "A map from replica node name to the lag, in" +
                           " VLSNs, between the replication state of the" +
                           " replica and the master, if known. Returns an" +
                           " empty map if this node is not the master.",
                           CUMULATIVE);

    public static StatDefinition REPLICA_VLSN_RATE_MAP =
        new StatDefinition("replicaVLSNRateMap",
                           "A map from replica node name to a moving average" +
                           " of the rate, in VLSNs per minute, that the" +
                           " replica is processing replication data, if" +
                           " known. Returns an empty map if this node is not" +
                           " the master.");
}

