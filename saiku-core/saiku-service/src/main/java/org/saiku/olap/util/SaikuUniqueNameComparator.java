/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import java.util.Comparator;
import org.saiku.olap.dto.ISaikuObject;

public class SaikuUniqueNameComparator implements Comparator<ISaikuObject> {

    public int compare(ISaikuObject o1, ISaikuObject o2) {
        return o1.getUniqueName().compareTo(o2.getUniqueName());
    }
}
