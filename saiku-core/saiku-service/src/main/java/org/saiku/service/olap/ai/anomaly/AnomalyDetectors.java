/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Registry / factory for {@link AnomalyDetector} implementations keyed by their
 * {@code method} identifier (saiku#907). Unknown methods raise an
 * {@link AiValidationException} carrying the candidate list so the REST layer
 * returns a self-correcting 400.
 */
public final class AnomalyDetectors {

    private static final Map<String, AnomalyDetector> REGISTRY = new LinkedHashMap<>();

    static {
        register(new ZScoreAnomalyDetector());
        register(new MadAnomalyDetector());
        // STL is registered but throws on detect() until a clean impl lands.
        register(new StlAnomalyDetector());
    }

    private AnomalyDetectors() {}

    private static void register(AnomalyDetector d) {
        REGISTRY.put(d.method().toLowerCase(java.util.Locale.ROOT), d);
    }

    /** Method identifiers known to the registry (includes the STL stub). */
    public static List<String> methods() {
        return List.copyOf(REGISTRY.keySet());
    }

    /**
     * Resolve a detector by method id (case-insensitive). Defaults to
     * {@code zscore} when {@code method} is null/blank.
     *
     * @throws AiValidationException with {@code field="method"} and the
     *     candidate list when the method is unknown
     */
    public static AnomalyDetector forMethod(String method) {
        String key = (method == null || method.isBlank())
                ? ZScoreAnomalyDetector.METHOD
                : method.trim().toLowerCase(java.util.Locale.ROOT);
        AnomalyDetector d = REGISTRY.get(key);
        if (d == null) {
            throw new AiValidationException(
                    "method", "Unknown anomaly method '" + method + "'. Supported: " + methods(), methods());
        }
        return d;
    }
}
