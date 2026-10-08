/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.service.olap.ai.ask.CertifiedQuery;
import org.saiku.service.olap.ai.ask.CertifiedQueryParser;

/**
 * saiku#1430 — the certified query bundled with the FoodMart demo. Lives here rather than in
 * saiku-service because the seed resource belongs to the launcher's resource root, and the launcher
 * is the module that stages it into {@code saiku-home/certified/} on first boot.
 *
 * <p>Its job is to fail loudly if the seed is edited into an unparseable state — a demo whose one
 * certified answer silently fails to load is worse than a demo with none, because the failure only
 * shows up as a routing miss at question time.
 */
public class CertifiedSeedTest {

    private static final String SEED = "seed/certified/monthly-store-sales-by-country.json";

    @Test
    public void bundledSeedParsesAndAnswersTheCfoQuestion() throws Exception {
        InputStream in = CertifiedSeedTest.class.getClassLoader().getResourceAsStream(SEED);
        assertNotNull("bundled certified seed not found at " + SEED, in);
        String json;
        try (InputStream stream = in) {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        CertifiedQuery q = CertifiedQueryParser.parse("seed", json);
        assertEquals("monthly-store-sales-by-country", q.id());
        assertEquals("Sales", q.cubeName());
        assertTrue(q.servesCube("Sales"));
        assertTrue(q.score("what was monthly revenue in the USA") > 0d);
        assertEquals(0d, q.score("how many units did we ship"), 0.0001d);
    }
}
