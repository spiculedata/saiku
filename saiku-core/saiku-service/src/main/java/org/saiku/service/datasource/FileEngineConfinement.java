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
package org.saiku.service.datasource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confinement of the file/lakehouse engines {@link JdbcUrlPolicy} allows (saiku#2320).
 *
 * <p>An embedded engine reads the host: DuckDB through {@code read_text} / {@code read_csv} /
 * {@code read_parquet} and, with {@code httpfs}, over HTTP (SSRF); H2 through a {@code file:}
 * database anywhere on disk. Admin-only datasource creation is not a boundary worth relying on,
 * and the SQL workbench (saiku#2318) reaches the same primitives as a non-admin, so the
 * lock-down lives at the connection chokepoint that every path shares:
 *
 * <ul>
 *   <li><b>DuckDB</b>: {@link #confineDuckDb} appends {@code enable_external_access=false},
 *       {@code allowed_directories=[<allowed roots>]} and {@code lock_configuration=true} to the
 *       URL, so the options apply when the database opens and a later {@code SET} cannot undo
 *       them. Local data under the allowed roots stays readable; other paths and all network
 *       access are refused. A URL-supplied setting loses to ours (the last one wins).
 *   <li><b>H2</b>: {@link #rejectH2PathOutsideRoots} refuses a file database outside the allowed
 *       roots ({@code mem:}, {@code tcp:} and {@code ssl:} URLs are not file URLs).
 * </ul>
 *
 * <p>Allowed roots are {@code ${saiku.home}} plus {@value #ALLOWED_ROOTS_PROPERTY} (platform path
 * separator). Operators opt out with {@code -D}{@value #CONFINEMENT_PROPERTY}{@code =false}. The
 * {@code jdbc:duckdb:r2://} upload URLs of saiku-cloud need {@code httpfs}, so they stay confined
 * unless {@value #ALLOW_R2_PROPERTY}{@code =true}. {@code jdbc:quack:} is a remote DuckDB client
 * and is not rewritten here.
 */
final class FileEngineConfinement {

    private static final Logger LOG = LoggerFactory.getLogger(FileEngineConfinement.class);

    /** {@code false} switches all file-engine confinement off. Default: on. */
    static final String CONFINEMENT_PROPERTY = "saiku.jdbc.fileEngineConfinement";

    /** Extra directories (platform path separator) DuckDB may read and H2 may keep databases in. */
    static final String ALLOWED_ROOTS_PROPERTY = "saiku.jdbc.allowedFileRoots";

    /** {@code true} exempts {@code jdbc:duckdb:r2://} URLs (they need httpfs). */
    static final String ALLOW_R2_PROPERTY = "saiku.jdbc.duckdb.allowR2Uploads";

    private static final String DUCKDB_PREFIX = "jdbc:duckdb:";
    private static final String DUCKDB_R2_PREFIX = "jdbc:duckdb:r2:";
    private static final String LOCKED_MARKER = "lock_configuration=true";

    /** H2 URL prefixes that name a local database file; stripped (repeatedly) to reach the path. */
    private static final Pattern H2_FILE_PREFIX =
            Pattern.compile("(?i)^(file:|nio:|niomapped:|async:|split:(\\d+:)?|zip:)");

    private static final Pattern H2_NON_FILE = Pattern.compile("(?i)^(mem:|tcp:|ssl:|//)");

    private static final Pattern MONDRIAN_JDBC_KEY = Pattern.compile("(?i)(?:^|;)\\s*Jdbc\\s*=");

    private FileEngineConfinement() {}

    static boolean isEnabled() {
        return !"false"
                .equalsIgnoreCase(
                        System.getProperty(CONFINEMENT_PROPERTY, "true").trim());
    }

    /** Rewrite a plain DuckDB URL, or the DuckDB {@code Jdbc=} part of a Mondrian wrapper. */
    static String confine(String url) {
        if (url == null || !isEnabled()) {
            return url;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(DUCKDB_PREFIX)) {
            return confineDuckDb(trimmed);
        }
        if (lower.startsWith("jdbc:mondrian:") || lower.startsWith("jdbc:mondrian4:")) {
            return confineMondrianWrapper(trimmed);
        }
        return url;
    }

    static String confineDuckDb(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(LOCKED_MARKER)) {
            return url;
        }
        if (lower.startsWith(DUCKDB_R2_PREFIX) && Boolean.getBoolean(ALLOW_R2_PROPERTY)) {
            LOG.warn("DuckDB r2:// upload URL left unconfined ({}=true)", ALLOW_R2_PROPERTY);
            return url;
        }
        String base = url.endsWith(";") ? url.substring(0, url.length() - 1) : url;
        // No known root: still lock external access, with no allowed directories (fail closed).
        String dirs = allowedRoots().isEmpty() ? "" : ";allowed_directories=" + duckDbDirectoryList();
        if (dirs.isEmpty()) {
            LOG.warn(
                    "saiku.home is unset and {} is empty: DuckDB local file access is fully disabled",
                    ALLOWED_ROOTS_PROPERTY);
        }
        return base + ";enable_external_access=false" + dirs + ";" + LOCKED_MARKER;
    }

    private static String confineMondrianWrapper(String url) {
        int bodyStart = url.indexOf(':', "jdbc:".length()) + 1; // after jdbc:mondrian[4]:
        Matcher m = MONDRIAN_JDBC_KEY.matcher(url).region(bodyStart, url.length());
        if (!m.find()) {
            return url;
        }
        int valueStart = m.end();
        Matcher next = JdbcUrlPolicy.MONDRIAN_KEY.matcher(url);
        int valueEnd = url.length();
        if (next.find(valueStart)) {
            valueEnd = next.start();
        }
        String value = url.substring(valueStart, valueEnd).trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith(DUCKDB_PREFIX)) {
            return url;
        }
        String confined = confineDuckDb(value);
        if (confined.equals(value)) {
            return url;
        }
        // Quote so Mondrian hands the ';'-bearing URL to the driver as ONE Jdbc= value.
        String quoted = "'" + confined.replace("'", "''") + "'";
        return url.substring(0, valueStart) + quoted + url.substring(valueEnd);
    }

    private static String duckDbDirectoryList() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Path root : allowedRoots()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('\'').append(root.toString().replace("'", "''")).append('\'');
            first = false;
        }
        return sb.append(']').toString();
    }

    /** {@code saiku.home} and the operator's extra roots, normalised. Empty when none is known. */
    static List<Path> allowedRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        String home = System.getProperty("saiku.home");
        if (home != null && !home.isBlank()) {
            roots.add(Paths.get(home.trim()));
        }
        String extra = System.getProperty(ALLOWED_ROOTS_PROPERTY);
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    roots.add(Paths.get(part.trim()));
                }
            }
        }
        List<Path> out = new ArrayList<>();
        for (Path r : roots) {
            out.add(r.toAbsolutePath().normalize());
        }
        return out;
    }

    /**
     * Refuse an H2 file database outside the allowed roots. {@code h2Url} is a {@code jdbc:h2:}
     * URL (not a wrapper). Unconfined only when confinement is off; when no root can be determined (a
     * plain WAR without {@code saiku.home}) it fails closed: the URL is rejected.
     */
    static void rejectH2PathOutsideRoots(String h2Url) {
        if (!isEnabled()) {
            return;
        }
        String path = h2FilePath(h2Url);
        if (path == null) {
            return;
        }
        List<Path> roots = allowedRoots();
        if (roots.isEmpty()) {
            // Fail closed: with no root to anchor against, "confined" would mean "anywhere".
            LOG.warn("Rejected H2 file database: saiku.home is unset and {} is empty", ALLOWED_ROOTS_PROPERTY);
            throw new IllegalArgumentException("Invalid datasource JDBC URL: H2 file databases need the saiku.home"
                    + " system property (or -D" + ALLOWED_ROOTS_PROPERTY + ") to be set (or opt out with -D"
                    + CONFINEMENT_PROPERTY + "=false)");
        }
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(roots);
        if (!guard.isAllowed(resolve(path))) {
            LOG.warn("Rejected H2 database path outside the permitted roots");
            throw new IllegalArgumentException("Invalid datasource JDBC URL: H2 database files must live under"
                    + " saiku.home (extend with -D" + ALLOWED_ROOTS_PROPERTY + ", or opt out with -D"
                    + CONFINEMENT_PROPERTY + "=false)");
        }
    }

    /** The file path an H2 URL names, or {@code null} for in-memory / remote databases. */
    private static String h2FilePath(String h2Url) {
        String rest = h2Url.substring(h2Url.toLowerCase(Locale.ROOT).indexOf("jdbc:h2:") + "jdbc:h2:".length());
        if (H2_NON_FILE.matcher(rest).find()) {
            return null;
        }
        Matcher prefix = H2_FILE_PREFIX.matcher(rest);
        while (prefix.find()) {
            rest = rest.substring(prefix.end());
            prefix = H2_FILE_PREFIX.matcher(rest);
        }
        int cut = rest.indexOf(';');
        String path = (cut >= 0 ? rest.substring(0, cut) : rest).trim();
        int bang = path.indexOf('!'); // zip:archive.zip!/db
        if (bang >= 0) {
            path = path.substring(0, bang);
        }
        return path.isEmpty() ? null : path;
    }

    private static Path resolve(String path) {
        String p = path;
        if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")) {
            p = System.getProperty("user.home", "") + p.substring(1);
        }
        return Paths.get(p).toAbsolutePath().normalize();
    }
}
