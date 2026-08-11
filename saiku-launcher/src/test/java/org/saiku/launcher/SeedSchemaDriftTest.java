/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * saiku#1662 — unit tests for the runtime-vs-seed FoodMart schema drift check.
 * The pure comparison ({@code runtimeSchemaIsStale}) is tested directly; the
 * boot-time WARN it drives is exercised by the launcher IT harness. The runtime
 * schema lives under the gitignored {@code saiku-home/} and is only ever
 * seeded-if-absent, so a fresh CI checkout has nothing to diff — the staleness
 * signal can only be a runtime check, which is what this guards.
 */
public class SeedSchemaDriftTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void identicalContentIsNotStale() {
        byte[] seed = bytes("<Schema name='FoodMart'/>");
        assertFalse(ServeCommand.runtimeSchemaIsStale(seed.clone(), seed));
    }

    @Test
    public void divergentContentIsStale() {
        assertTrue(ServeCommand.runtimeSchemaIsStale(bytes("<Schema name='old'/>"), bytes("<Schema name='new'/>")));
    }

    @Test
    public void trailingByteDifferenceIsStale() {
        // A single appended byte (e.g. CRLF vs LF or a new MeasureGroup) counts.
        assertTrue(ServeCommand.runtimeSchemaIsStale(bytes("<Schema/>"), bytes("<Schema/>\n")));
    }

    @Test
    public void missingOrEmptyEitherSideIsNeverStale() {
        byte[] present = bytes("x");
        assertFalse("null runtime must not block boot", ServeCommand.runtimeSchemaIsStale(null, present));
        assertFalse("null seed must not block boot", ServeCommand.runtimeSchemaIsStale(present, null));
        assertFalse("empty runtime is not a comparison", ServeCommand.runtimeSchemaIsStale(new byte[0], present));
        assertFalse("empty seed is not a comparison", ServeCommand.runtimeSchemaIsStale(present, new byte[0]));
    }
}
