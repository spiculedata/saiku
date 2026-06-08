/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.saiku.service.olap.ai.AiValidationException;

/** saiku#907 — detector registry + STL stub behaviour. */
public class AnomalyDetectorsTest {

    @Test
    public void resolvesKnownMethodsCaseInsensitively() {
        assertEquals("zscore", AnomalyDetectors.forMethod("zscore").method());
        assertEquals("zscore", AnomalyDetectors.forMethod("ZScore").method());
        assertEquals("mad", AnomalyDetectors.forMethod("MAD").method());
        assertEquals("stl", AnomalyDetectors.forMethod("stl").method());
    }

    @Test
    public void defaultsToZScoreWhenMethodBlank() {
        assertEquals("zscore", AnomalyDetectors.forMethod(null).method());
        assertEquals("zscore", AnomalyDetectors.forMethod("  ").method());
    }

    @Test
    public void unknownMethodRaisesValidationErrorWithCandidates() {
        try {
            AnomalyDetectors.forMethod("bananas");
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("method", e.getField());
            assertTrue(e.getAvailable().contains("zscore"));
            assertTrue(e.getAvailable().contains("mad"));
        }
    }

    @Test
    public void stlStubThrowsClearValidationError() {
        AnomalyDetector stl = AnomalyDetectors.forMethod("stl");
        try {
            stl.detect(new double[] {1, 2, 3}, 3.0);
            fail("expected AiValidationException from STL stub");
        } catch (AiValidationException e) {
            assertEquals("method", e.getField());
            assertTrue(e.getMessage().toLowerCase().contains("not yet supported"));
            // Suggests the methods that DO work.
            assertTrue(e.getAvailable().contains("zscore"));
            assertTrue(e.getAvailable().contains("mad"));
        }
    }
}
