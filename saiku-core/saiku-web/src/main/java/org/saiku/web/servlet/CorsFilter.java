/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter that attaches CORS response headers for the Saiku REST API
 * ({@code /rest/saiku/api/*} and {@code /rest/saiku/info/*}).
 *
 * <p>Reads the allowed-origin allow-list from the JVM system property
 * {@code saiku.cors.allowedOrigins} (comma-separated, or {@code *} for
 * wildcard). When the property is absent or blank, all CORS requests pass
 * through without headers (effectively blocking cross-origin preflight).
 *
 * <p>OPTIONS preflight requests are short-circuited here: {@code 204 No Content}
 * if the origin is allowed, {@code 403 Forbidden} otherwise.
 */
public class CorsFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String allow =
                CorsSupport.allowedOrigin(request.getHeader("Origin"), System.getProperty("saiku.cors.allowedOrigins"));

        if (allow != null) {
            response.setHeader("Access-Control-Allow-Origin", allow);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", CorsSupport.ALLOW_METHODS);
            response.setHeader("Access-Control-Allow-Headers", CorsSupport.ALLOW_HEADERS);
            response.setHeader("Access-Control-Max-Age", CorsSupport.MAX_AGE);
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(allow != null ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        chain.doFilter(req, res);
    }
}
