/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CellLinkUrlTest {

    @Test
    public void fromAnnotationMap_readsSaikuCellLinkUrl() {
        Map<String, String> ann = new HashMap<>();
        ann.put("saiku.cellLink.url", " https://erp.ejemplo/ops?barrio={Barrio} ");
        assertEquals("https://erp.ejemplo/ops?barrio={Barrio}", CellLinkUrl.fromAnnotationMap(ann));
    }

    @Test
    public void fromAnnotationMap_ignoresOtherKeys() {
        Map<String, String> ann = new HashMap<>();
        ann.put("saiku.semantic.description", "ops cube");
        assertNull(CellLinkUrl.fromAnnotationMap(ann));
    }

    @Test
    public void fromAnnotationMap_blankAndNullAreNull() {
        assertNull(CellLinkUrl.fromAnnotationMap(null));
        Map<String, String> blank = new HashMap<>();
        blank.put("saiku.cellLink.url", "  ");
        assertNull(CellLinkUrl.fromAnnotationMap(blank));
    }

    @Test
    public void preferCubeThenSds_cubeWinsOverSds() {
        assertEquals("https://from.cube/x", CellLinkUrl.preferCubeThenSds("https://from.cube/x", "https://from.sds/x"));
    }

    @Test
    public void preferCubeThenSds_fallsBackToSds() {
        assertEquals("https://from.sds/x", CellLinkUrl.preferCubeThenSds(null, " https://from.sds/x "));
        assertEquals("https://from.sds/x", CellLinkUrl.preferCubeThenSds("  ", "https://from.sds/x"));
    }

    @Test
    public void preferCubeThenSds_bothBlankIsNull() {
        assertNull(CellLinkUrl.preferCubeThenSds(null, null));
        assertNull(CellLinkUrl.preferCubeThenSds(" ", ""));
    }
}
