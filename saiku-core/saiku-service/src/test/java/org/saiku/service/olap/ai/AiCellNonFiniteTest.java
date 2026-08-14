/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.Test;

/**
 * saiku#1780 — non-finite measure values must not reach the wire.
 *
 * <p>A divide-by-zero calculated measure evaluates to {@code Infinity}. Jackson
 * writes that as the bare token {@code Infinity}, which RFC 8259 does not allow —
 * strict parsers reject the whole response — and lenient clients then print the
 * literal string "Infinity" into a KPI headline or table cell. FoodMart's
 * {@code Stock To Sales Ratio} does exactly this when sliced by a warehouse
 * geography.
 */
public class AiCellNonFiniteTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void infinityBecomesNull() {
        assertNull(new AiCell(Double.POSITIVE_INFINITY, "Infinity", null).getValue());
        assertNull(new AiCell(Double.NEGATIVE_INFINITY, "-Infinity", null).getValue());
    }

    @Test
    public void nanBecomesNull() {
        assertNull(new AiCell(Double.NaN, "NaN", null).getValue());
    }

    @Test
    public void finiteValuesAreUntouched() {
        assertEquals(Double.valueOf(0.35), new AiCell(0.35, "0.35", null).getValue());
        assertEquals(Double.valueOf(0.0), new AiCell(0.0, "0.00", null).getValue());
        assertEquals(Double.valueOf(-12.5), new AiCell(-12.5, "-12.50", null).getValue());
    }

    @Test
    public void thePropertiesConstructorClampsToo() {
        AiCell c = new AiCell(Double.POSITIVE_INFINITY, "Infinity", null, Collections.emptyMap());
        assertNull(c.getValue());
    }

    @Test
    public void theSetterClampsToo() {
        AiCell c = new AiCell();
        c.setValue(Double.POSITIVE_INFINITY);
        assertNull(c.getValue());
    }

    @Test
    public void serialisedCellIsValidJsonWithANullValue() throws Exception {
        String json = mapper.writeValueAsString(new AiCell(Double.POSITIVE_INFINITY, "Infinity", null));
        assertTrue("must not emit the bare Infinity token: " + json, !json.contains(":Infinity"));
        // Round-trips through a strict parser.
        assertNull(mapper.readTree(json).get("value").isNull() ? null : "not null");
        // The server's own rendering stays visible for debugging.
        assertEquals("Infinity", mapper.readTree(json).get("formatted").asText());
    }
}
