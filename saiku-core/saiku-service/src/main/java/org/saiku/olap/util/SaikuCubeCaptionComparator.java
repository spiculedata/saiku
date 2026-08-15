/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.saiku.olap.util;

import java.util.Comparator;
import org.saiku.olap.dto.SaikuCube;

public class SaikuCubeCaptionComparator implements Comparator<SaikuCube> {

    public int compare(SaikuCube o1, SaikuCube o2) {
        // saiku#1851: a null caption used to short-circuit to 0, which is NOT transitive — a null
        // compares "equal" to every caption while those captions are not equal to each other. That
        // breaks the Comparator contract, and TimSort detects it at runtime and throws
        // IllegalArgumentException("Comparison method violates its general contract!") for some
        // inputs at some list sizes. Safe until now only because olap4j always supplies a caption.
        // Ordering nulls last is total, transitive, and consistent with equals.
        String c1 = o1 == null ? null : o1.getCaption();
        String c2 = o2 == null ? null : o2.getCaption();
        if (c1 == null) {
            return c2 == null ? 0 : 1;
        }
        if (c2 == null) {
            return -1;
        }
        return c1.compareTo(c2);
    }
}
