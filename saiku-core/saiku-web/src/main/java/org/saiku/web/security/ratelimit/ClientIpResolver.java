/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that decides which address a request "comes from", shared by
 * {@link LoginRateLimiter} and the audit log so the two never disagree (saiku#2427).
 *
 * <p>The client is the TCP peer ({@link HttpServletRequest#getRemoteAddr()}) unless
 * {@code saiku.auth.trustedProxyHops=<n>} (n greater than 0) says that the last <em>n</em>
 * hops are proxies you control, counting the immediate peer (one nginx is {@code 1}).
 * Then the client is the {@code n}-th entry from the right of {@code X-Forwarded-For}, taken
 * over <em>all</em> header lines joined in order (a proxy may add a line instead of
 * appending to the first). Entries to its left are caller-supplied and never used. With
 * fewer than {@code n} entries the peer address is used. The legacy
 * {@code saiku.auth.trustForwardedFor=true} is an alias for {@code n=1}.
 */
public final class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {}

    /** The configured number of trusted proxy hops, read from system properties on every call. */
    public static int configuredTrustedProxyHops() {
        String configured = System.getProperty("saiku.auth.trustedProxyHops");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException e) {
                log.warn(
                        "ignoring invalid saiku.auth.trustedProxyHops='{}'; forwarded headers are not trusted",
                        configured);
                return 0;
            }
        }
        return Boolean.parseBoolean(System.getProperty("saiku.auth.trustForwardedFor", "false")) ? 1 : 0;
    }

    /** The client address using the configured number of trusted hops. */
    public static String resolve(HttpServletRequest req) {
        return resolve(req, configuredTrustedProxyHops());
    }

    /** The client address given {@code trustedProxyHops} trusted hops (0 means the TCP peer). */
    public static String resolve(HttpServletRequest req, int trustedProxyHops) {
        if (trustedProxyHops > 0) {
            List<String> hops = forwardedForEntries(req);
            if (hops.size() >= trustedProxyHops) {
                return hops.get(hops.size() - trustedProxyHops);
            }
        }
        return req.getRemoteAddr();
    }

    private static List<String> forwardedForEntries(HttpServletRequest req) {
        List<String> hops = new ArrayList<>();
        Enumeration<String> lines = req.getHeaders(FORWARDED_FOR);
        if (lines == null) return hops;
        while (lines.hasMoreElements()) {
            String line = lines.nextElement();
            if (line == null) continue;
            for (String part : line.split(",")) {
                if (!part.isBlank()) hops.add(part.trim());
            }
        }
        return hops;
    }
}
