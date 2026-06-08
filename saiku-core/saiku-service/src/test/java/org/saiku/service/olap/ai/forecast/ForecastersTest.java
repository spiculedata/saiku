/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.saiku.service.olap.ai.AiValidationException;

/** Locks the forecaster registry + stub behaviour (saiku#908). */
public class ForecastersTest {

    @Test
    public void defaultsToEts() {
        assertEquals("ets", Forecasters.forMethod(null).method());
        assertEquals("ets", Forecasters.forMethod("").method());
        assertEquals("ets", Forecasters.forMethod("  ").method());
    }

    @Test
    public void resolvesCaseInsensitively() {
        assertEquals("ets", Forecasters.forMethod("ETS").method());
        assertEquals("arima", Forecasters.forMethod("Arima").method());
    }

    @Test
    public void unknownMethod_throwsWithCandidates() {
        try {
            Forecasters.forMethod("magic");
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("method", e.getField());
            assertTrue(e.getAvailable().contains("ets"));
        }
    }

    @Test
    public void registryListsAllMethods() {
        assertTrue(Forecasters.methods().containsAll(java.util.List.of("ets", "arima", "prophet")));
    }

    @Test
    public void arimaStub_throwsValidation_suggestingEts() {
        try {
            Forecasters.forMethod("arima").forecast(new double[] {1, 2, 3}, 3, 0.95);
            fail("expected AiValidationException from ARIMA stub");
        } catch (AiValidationException e) {
            assertEquals("method", e.getField());
            assertTrue(e.getAvailable().contains("ets"));
        }
    }

    @Test
    public void prophetStub_throwsValidation() {
        try {
            Forecasters.forMethod("prophet").forecast(new double[] {1, 2, 3}, 3, 0.95);
            fail("expected AiValidationException from Prophet stub");
        } catch (AiValidationException e) {
            assertEquals("method", e.getField());
        }
    }
}
