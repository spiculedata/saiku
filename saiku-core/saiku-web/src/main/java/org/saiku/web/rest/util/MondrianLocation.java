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
package org.saiku.web.rest.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Key-based parser for a Mondrian {@code location} connect string:
 *
 * <pre>jdbc:mondrian:Jdbc=&lt;jdbcUrl&gt;;Catalog=&lt;catalog&gt;;JdbcDrivers=&lt;drivers&gt;</pre>
 *
 * <p>The naive {@code location.split(";")} positional parse this replaces broke whenever the inner
 * {@code Jdbc=} value carried its own {@code ;}-separated JDBC parameters — e.g. the launcher's
 * FoodMart datasource {@code jdbc:mondrian:Jdbc=jdbc:h2:.../foodmart;MODE=MySQL;Catalog=file:...
 * FoodMart4.xml;JdbcDrivers=org.h2.Driver}. There the {@code ;MODE=MySQL} param shifted the
 * positional segments so {@code Catalog} was read as the driver and {@code MODE=MySQL} as the
 * catalog (saiku#1634).
 *
 * <p>This parser instead anchors on the three known Mondrian keys ({@code Jdbc}, {@code Catalog},
 * {@code JdbcDrivers}) wherever they appear, treating each value as running up to the next known
 * key — so unknown {@code ;key=value} params (like H2's {@code MODE}) stay part of the value they
 * belong to, and key ordering does not matter. Values are otherwise returned verbatim (no
 * unescaping), matching the previous behaviour for the fields it feeds.
 */
public final class MondrianLocation {

    /** Mondrian location URLs start with this scheme prefix. */
    public static final String MONDRIAN_PREFIX = "jdbc:mondrian:";

    // A known key either opens the body (^) or follows a ';'. The value runs to the next such key.
    private static final Pattern KEY = Pattern.compile("(?:^|;)(Jdbc|Catalog|JdbcDrivers)=");

    private final String jdbc;
    private final String catalog;
    private final String jdbcDrivers;

    private MondrianLocation(String jdbc, String catalog, String jdbcDrivers) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.jdbcDrivers = jdbcDrivers;
    }

    /**
     * Parse a Mondrian location string. Non-Mondrian or blank input yields an all-null result
     * (callers treat that as "no Mondrian coordinates"). Missing individual keys are null.
     */
    public static MondrianLocation parse(String location) {
        if (location == null || !location.startsWith(MONDRIAN_PREFIX)) {
            return new MondrianLocation(null, null, null);
        }
        String body = location.substring(MONDRIAN_PREFIX.length());
        Matcher m = KEY.matcher(body);

        // First pass: collect each key, the index its value starts at, and the index the whole
        // "(^|;)Key=" match starts at (used as the previous value's end so the ';' is dropped).
        java.util.List<String> keys = new java.util.ArrayList<>(3);
        java.util.List<Integer> valueStarts = new java.util.ArrayList<>(3);
        java.util.List<Integer> matchStarts = new java.util.ArrayList<>(3);
        while (m.find()) {
            keys.add(m.group(1));
            valueStarts.add(m.end());
            matchStarts.add(m.start());
        }

        String jdbc = null;
        String catalog = null;
        String jdbcDrivers = null;
        for (int i = 0; i < keys.size(); i++) {
            int end = (i + 1 < keys.size()) ? matchStarts.get(i + 1) : body.length();
            String value = body.substring(valueStarts.get(i), end);
            switch (keys.get(i)) {
                case "Jdbc" -> jdbc = value;
                case "Catalog" -> catalog = value;
                case "JdbcDrivers" -> jdbcDrivers = value;
                default -> {
                    /* pattern only matches the three known keys */
                }
            }
        }
        return new MondrianLocation(jdbc, catalog, jdbcDrivers);
    }

    /** The inner {@code Jdbc=} JDBC URL (may itself contain {@code ;}-separated params), or null. */
    public String jdbc() {
        return jdbc;
    }

    /** The {@code Catalog=} value verbatim — e.g. {@code file:/path/Foo.xml}, {@code res:Foo.xml},
     *  or {@code mondrian://Foo} — or null when absent. */
    public String catalog() {
        return catalog;
    }

    /** The {@code JdbcDrivers=} value (driver class name), or null when absent. */
    public String jdbcDrivers() {
        return jdbcDrivers;
    }

    /**
     * Rebuild this location with a different {@code Catalog=}, keeping the JDBC URL and driver
     * exactly as they were.
     *
     * <p>saiku#1872: the Cube Designer's query preview needs to run an unsaved schema against the
     * datasource the user is designing against. Reusing the stored location and swapping only the
     * catalog means the preview connects with the SAME warehouse, credentials and driver as the
     * real thing — and, critically, means the JDBC URL can never come from the request. A preview
     * endpoint that accepted a URL would be a way to make the server connect anywhere.
     *
     * @param newCatalog the catalog reference to use, e.g. a {@code mondrian://} repository path
     * @return a full {@code jdbc:mondrian:...} location string
     * @throws IllegalStateException if this location had no {@code Jdbc=} component to reuse
     */
    public String withCatalog(String newCatalog) {
        if (jdbc == null || jdbc.isBlank()) {
            throw new IllegalStateException("cannot build a Mondrian location without a Jdbc= component");
        }
        StringBuilder out = new StringBuilder(MONDRIAN_PREFIX);
        out.append("Jdbc=").append(jdbc);
        if (newCatalog != null && !newCatalog.isBlank()) {
            out.append(";Catalog=").append(newCatalog);
        }
        if (jdbcDrivers != null && !jdbcDrivers.isBlank()) {
            out.append(";JdbcDrivers=").append(jdbcDrivers);
        }
        return out.toString();
    }
}
