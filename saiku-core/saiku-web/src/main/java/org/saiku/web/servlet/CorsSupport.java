/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import java.util.Arrays;

/**
 * Pure CORS allow-origin logic, free of servlet dependencies so it can be unit-tested
 * without a container.
 *
 * <p>Usage: call {@link #allowedOrigin(String, String)} and, if non-null, write the
 * returned value as the {@code Access-Control-Allow-Origin} header.
 */
public final class CorsSupport {

    private CorsSupport() {}

    /** Methods advertised in CORS preflight responses. */
    public static final String ALLOW_METHODS = "GET, POST, OPTIONS";

    /** Headers advertised in CORS preflight responses. */
    public static final String ALLOW_HEADERS = "Authorization, Content-Type, X-Requested-With";

    /** Preflight cache duration in seconds. */
    public static final String MAX_AGE = "600";

    /**
     * Returns the origin to echo back as {@code Access-Control-Allow-Origin}, or
     * {@code null} if the request should not receive CORS headers.
     *
     * @param requestOrigin the value of the incoming {@code Origin} header (may be null)
     * @param allowedCsv    a comma-separated list of allowed origins, or {@code "*"} for
     *                      wildcard (may be null/blank, meaning CORS is disabled)
     * @return the origin to reflect, or {@code null} if not allowed
     */
    public static String allowedOrigin(String requestOrigin, String allowedCsv) {
        if (requestOrigin == null || requestOrigin.isBlank()) return null;
        if (allowedCsv == null || allowedCsv.isBlank()) return null;
        String origin = requestOrigin.trim();
        return Arrays.stream(allowedCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .anyMatch(a -> a.equals("*") || a.equals(origin))
                ? origin
                : null;
    }
}
