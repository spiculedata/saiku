/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Registry / factory for {@link Forecaster} implementations keyed by their
 * {@code method} id (saiku#908). Unknown methods raise an
 * {@link AiValidationException} carrying the candidate list so the REST layer
 * returns a self-correcting 400. Mirrors {@code AnomalyDetectors}.
 */
public final class Forecasters {

    private static final Map<String, Forecaster> REGISTRY = new LinkedHashMap<>();

    static {
        register(new EtsForecaster());
        // arima + prophet are registered but throw on forecast() until impls land.
        register(new ArimaForecaster());
        register(new ProphetForecaster());
    }

    private Forecasters() {}

    private static void register(Forecaster f) {
        REGISTRY.put(f.method().toLowerCase(Locale.ROOT), f);
    }

    /** Method ids known to the registry (includes the arima/prophet stubs). */
    public static List<String> methods() {
        return List.copyOf(REGISTRY.keySet());
    }

    /**
     * Resolve a forecaster by method id (case-insensitive). Defaults to
     * {@code ets} when {@code method} is null/blank.
     *
     * @throws AiValidationException with {@code field="method"} + the candidate
     *     list when the method is unknown
     */
    public static Forecaster forMethod(String method) {
        String key = (method == null || method.isBlank())
                ? EtsForecaster.METHOD
                : method.trim().toLowerCase(Locale.ROOT);
        Forecaster f = REGISTRY.get(key);
        if (f == null) {
            throw new AiValidationException(
                    "method", "Unknown forecast method '" + method + "'. Supported: " + methods(), methods());
        }
        return f;
    }
}
