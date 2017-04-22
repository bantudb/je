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

package com.sleepycat.je.rep.utilint;

import com.sleepycat.je.utilint.StatDefinition;

/**
 * Per-stat Metadata for each BinaryProtocol statistics.
 */
public class BinaryProtocolStatDefinition {

    public static final String GROUP_NAME = "BinaryProtocol";
    public static final String GROUP_DESC =
        "Network traffic due to the replication stream.";

    public static final StatDefinition N_READ_NANOS =
        new StatDefinition
        ("nReadNanos",
         "The number of nanoseconds spent reading from the network channel.");

    public static final StatDefinition N_WRITE_NANOS =
        new StatDefinition
        ("nWriteNanos",
         "The number of nanoseconds spent writing to the network channel.");

    public static final StatDefinition N_BYTES_READ =
        new StatDefinition
        ("nBytesRead",
         "The number of bytes of Replication Stream read over the network. " +
         "It does not include the TCP/IP overhead.");

    public static final StatDefinition N_MESSAGES_READ =
        new StatDefinition
        ("nMessagesRead",
         "The number of Replication Stream messages read over the network.");

    public static final StatDefinition N_BYTES_WRITTEN =
        new StatDefinition
        ("nBytesWritten",
         "The number of Replication Stream bytes written over the network.");

    public static final StatDefinition N_MESSAGES_WRITTEN =
        new StatDefinition
        ("nMessagesWritten",
         "The total number of Replication Stream messages written over the " +
         "network.");

    public static final StatDefinition N_MESSAGES_BATCHED =
        new StatDefinition
        ("nMessagesBatched",
         "The number of Replication Stream messages that were batched " +
         "into larger network level writes instead of being " +
         "written individually (a subset of N_MESSAGES_WRITTEN.)");

    public static final StatDefinition N_MESSAGE_BATCHES =
        new StatDefinition
        ("nMessageBatches",
         "The number of message batches written.");

    public static final StatDefinition MESSAGE_READ_RATE =
        new StatDefinition
        ("messagesReadPerSecond", "Incoming message throughput.");

    public static final StatDefinition MESSAGE_WRITE_RATE =
        new StatDefinition
        ("messagesWrittenPerSecond", "Outgoing message throughput.");

    public static final StatDefinition BYTES_READ_RATE =
        new StatDefinition
        ("bytesReadPerSecond", "Bytes read throughput.");

    public static final StatDefinition BYTES_WRITE_RATE =
        new StatDefinition
        ("bytesWrittenPerSecond", "Bytes written throughput.");

    public static final StatDefinition N_ACK_MESSAGES =
        new StatDefinition
        ("nAckMessages", "Count of all singleton ACK messages.");

    public static final StatDefinition N_GROUP_ACK_MESSAGES =
        new StatDefinition
        ("nGroupAckMessages", "Count of all group ACK messages.");

    public static final StatDefinition N_MAX_GROUPED_ACKS =
        new StatDefinition
        ("nMaxGroupedAcks", "Max number of acks sent via a single "
            + "group ACK message.");

    public static final StatDefinition N_GROUPED_ACKS =
        new StatDefinition
        ("nGroupedAcks", "Sum of all acks sent via group ACK messages.");

    public static final StatDefinition N_ENTRIES_WRITTEN_OLD_VERSION =
        new StatDefinition(
            "nEntriesOldVersion",
            "The number of messages containing log entries that were written" +
            " to the replication stream using the previous log format, to" +
            " support replication to a replica running an earlier version" +
            " during an upgrade.");
}
