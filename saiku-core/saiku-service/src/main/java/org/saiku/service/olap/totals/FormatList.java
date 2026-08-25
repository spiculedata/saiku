/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals;

import mondrian.util.Format;

interface FormatList {
    Format getValueFormat(int position, int member);
}
