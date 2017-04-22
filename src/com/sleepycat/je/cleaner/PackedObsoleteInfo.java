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

import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.MemoryBudget;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.utilint.DbLsn;

/**
 * A sequence of obsolete info.
 *
 * To save memory, a TupleOutput is used to contain a sequence of {LSN-file,
 * LSN-offset, isLN, size} tuples. Packed integers are used and memory is saved
 * by not using an Object for each tuple, as would be needed in a Java
 * collection.
 *
 * An OffsetList was not used because it does not use packed integers.
 * PackedOffsets was not used because it depends on offsets being sorted in
 * ascending order.
 */
public class PackedObsoleteInfo extends TupleOutput {

    public PackedObsoleteInfo() {
    }

    public int getMemorySize() {
        return MemoryBudget.tupleOutputSize(this);
    }

    public void copyObsoleteInfo(final PackedObsoleteInfo other) {
        writeFast(other.getBufferBytes(),
                  other.getBufferOffset(),
                  other.getBufferLength());
    }

    public void addObsoleteInfo(
        final long obsoleteLsn,
        final boolean isObsoleteLN,
        final int obsoleteSize) {
        
        writePackedLong(DbLsn.getFileNumber(obsoleteLsn));
        writePackedLong(DbLsn.getFileOffset(obsoleteLsn));
        writeBoolean(isObsoleteLN);
        writePackedInt(obsoleteSize);
    }

    public void countObsoleteInfo(
        final UtilizationTracker tracker,
        final DatabaseImpl nodeDb) {

        final TupleInput in = new TupleInput(this);

        while (in.available() > 0) {
            final long fileNumber = in.readPackedLong();
            long fileOffset = in.readPackedLong();
            final boolean isObsoleteLN = in.readBoolean();
            final int obsoleteSize = in.readPackedInt();

            tracker.countObsoleteNode(
                DbLsn.makeLsn(fileNumber, fileOffset),
                (isObsoleteLN ? 
                 LogEntryType.LOG_INS_LN /* Any LN type will do */ : 
                 LogEntryType.LOG_IN),
                obsoleteSize, nodeDb);
        }
    }
}
