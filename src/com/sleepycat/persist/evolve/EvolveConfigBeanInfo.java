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

package com.sleepycat.persist.evolve;

import com.sleepycat.util.ConfigBeanInfoBase;

import java.beans.BeanDescriptor;
import java.beans.PropertyDescriptor;

public class EvolveConfigBeanInfo extends ConfigBeanInfoBase {

    @Override
    public BeanDescriptor getBeanDescriptor() {
        return getBdescriptor(EvolveConfig.class);
    }

    @Override
    public PropertyDescriptor[] getPropertyDescriptors() {
        return getPdescriptor(EvolveConfig.class);
    }
}
