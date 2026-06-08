/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.objects;

import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralised validation for admin-supplied datasource JDBC URLs / connection locations.
 *
 * <p>An admin can configure a datasource whose JDBC URL eventually reaches
 * {@code DriverManager.getConnection(...)}. The H2 driver in particular treats certain URL
 * parameters as executable instructions: {@code ;INIT=RUNSCRIPT FROM '...'},
 * {@code CREATE ALIAS}/{@code CREATE TRIGGER} (Java stored procedures), and {@code ;SHUTDOWN}.
 * These turn an otherwise data-only connection string into arbitrary code / lifecycle control on
 * the server — an admin-gated but confirmed RCE vector.
 *
 * <p>This validator HARD-rejects the dangerous H2 tokens (including when the H2 URL is nested
 * inside a {@code jdbc:mondrian:Jdbc=...} wrapper) and applies a permissive, warn-only scheme
 * allow-list so legitimate backends are never broken.
 */
public final class JdbcUrlValidator {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcUrlValidator.class);

    private JdbcUrlValidator() {}

    /** Dangerous H2 tokens. Matched case-insensitively against the (sub-)URL. */
    private static final Pattern H2_INIT = Pattern.compile("(?i)\\bINIT\\s*=");

    private static final Pattern H2_RUNSCRIPT = Pattern.compile("(?i)\\bRUNSCRIPT\\b");

    private static final Pattern H2_CREATE_EXEC = Pattern.compile("(?i)\\bCREATE\\s+(ALIAS|TRIGGER|FORCE)\\b");

    private static final Pattern H2_SHUTDOWN = Pattern.compile("(?i);\\s*SHUTDOWN\\b");

    /**
     * Permissive (warn-only) allow-list of recognised JDBC sub-schemes / driver schemes. Unknown
     * schemes are logged but accepted, so custom backends keep working; only the dangerous H2
     * tokens are a hard failure.
     */
    private static final String[] KNOWN_SCHEMES = {
        "jdbc:h2:",
        "jdbc:postgresql:",
        "jdbc:mysql:",
        "jdbc:mariadb:",
        "jdbc:sqlserver:",
        "jdbc:jtds:",
        "jdbc:oracle:",
        "jdbc:hsqldb:",
        "jdbc:mondrian:",
        "jdbc:xmla:"
    };

    /**
     * Validate an admin-supplied JDBC URL or raw {@code location=} value.
     *
     * @param jdbcUrlOrLocation the JDBC URL or connection location (may be a Mondrian/XMLA wrapper)
     * @throws IllegalArgumentException if the URL contains a dangerous H2 instruction token
     */
    public static void validate(String jdbcUrlOrLocation) {
        if (jdbcUrlOrLocation == null) {
            return;
        }
        String url = jdbcUrlOrLocation.trim();
        if (url.isEmpty()) {
            return;
        }

        // Reject on the whole string first (covers any layer / nesting), then specifically the
        // inner jdbc sub-URL nested in a mondrian wrapper (jdbc:mondrian:Jdbc=jdbc:h2:...).
        rejectDangerousH2Tokens(url);
        String inner = extractMondrianInnerJdbc(url);
        if (inner != null) {
            rejectDangerousH2Tokens(inner);
        }

        warnOnUnknownScheme(url, inner);
    }

    private static void rejectDangerousH2Tokens(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        // Only the H2 tokens are weaponised through the H2 driver; gate on an H2 sub-URL being
        // present anywhere in the (possibly wrapped) string.
        boolean looksH2 = lower.contains("jdbc:h2:");
        if (!looksH2) {
            return;
        }
        if (H2_INIT.matcher(url).find()) {
            throw reject(url, "H2 INIT= run-on-connect is not permitted");
        }
        if (H2_RUNSCRIPT.matcher(url).find()) {
            throw reject(url, "H2 RUNSCRIPT is not permitted");
        }
        if (H2_CREATE_EXEC.matcher(url).find()) {
            throw reject(url, "H2 CREATE ALIAS/TRIGGER/FORCE is not permitted");
        }
        if (H2_SHUTDOWN.matcher(url).find()) {
            throw reject(url, "H2 SHUTDOWN is not permitted");
        }
    }

    private static IllegalArgumentException reject(String url, String reason) {
        // Do not echo the full (potentially sensitive) URL into the exception message.
        LOG.warn("Rejected datasource JDBC URL: {}", reason);
        return new IllegalArgumentException("Invalid datasource JDBC URL: " + reason);
    }

    /**
     * Pull the nested JDBC sub-URL out of a {@code jdbc:mondrian:Jdbc=<sub-url>;Catalog=...} string.
     * Returns {@code null} when the input is not a mondrian wrapper.
     */
    private static String extractMondrianInnerJdbc(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("jdbc=");
        if (idx < 0) {
            return null;
        }
        int start = idx + "jdbc=".length();
        // The inner JDBC URL itself can contain ';' (e.g. H2 settings), so we cannot simply cut at
        // the first ';'. Cut at the next mondrian wrapper key instead, falling back to end-of-string.
        int end = url.length();
        int catalog = lower.indexOf(";catalog=", start);
        int drivers = lower.indexOf(";jdbcdrivers=", start);
        if (catalog >= 0) {
            end = Math.min(end, catalog);
        }
        if (drivers >= 0) {
            end = Math.min(end, drivers);
        }
        return url.substring(start, end);
    }

    private static void warnOnUnknownScheme(String url, String inner) {
        if (matchesKnownScheme(url) || (inner != null && matchesKnownScheme(inner))) {
            return;
        }
        LOG.warn("Datasource JDBC URL uses an unrecognised scheme; allowing (warn-only allow-list).");
    }

    private static boolean matchesKnownScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT).trim();
        for (String scheme : KNOWN_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }
}
