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
 * database anywhere on disk; the other embedded engines (SQLite, Derby, HSQLDB) likewise name a
 * database file in their URL. Admin-only datasource creation is not a boundary worth relying on,
 * and the SQL workbench (saiku#2318) reaches the same primitives as a non-admin, so the lock-down
 * lives at the connection chokepoint that every path shares:
 *
 * <ul>
 *   <li><b>DuckDB</b>: {@link #confineDuckDb} strips any URL-supplied copy of a confinement key,
 *       then appends {@code enable_external_access=false},
 *       {@code allowed_directories=[<allowed roots>]} and {@code lock_configuration=true}, so the
 *       options apply when the database opens and a later {@code SET} cannot undo them. The
 *       rewrite is idempotent by construction — the operator's keys are removed first, so exactly
 *       one copy of each option, ours, survives. Local data under the allowed roots stays
 *       readable; other paths and all network access are refused. {@link JdbcUrlPolicy#validate}
 *       additionally <em>refuses</em> a user-supplied confinement key outright, on a DuckDB or
 *       Quack URL and in the info property map.
 *   <li><b>H2 / SQLite / Derby / HSQLDB</b>: {@link #rejectFileEnginePathOutsideRoots} refuses a
 *       file database outside the allowed roots (in-memory and remote URLs are not file URLs).
 * </ul>
 *
 * <p>Allowed roots are {@code ${saiku.home}} plus {@value #ALLOWED_ROOTS_PROPERTY} (platform path
 * separator). Operators opt out with {@code -D}{@value #CONFINEMENT_PROPERTY}{@code =false}. The
 * {@code jdbc:duckdb:r2://} upload URLs of saiku-cloud need {@code httpfs}, so they stay confined
 * unless {@value #ALLOW_R2_PROPERTY}{@code =true} (a global opt-in; scoping it per datasource
 * needs driver-knob semantics DuckDB does not offer, so the flag is documented and deliberate).
 * {@code jdbc:quack:} is a remote DuckDB client — host-file access happens on the server it talks
 * to, not Saiku's host — so it is not rewritten, but user-supplied confinement keys are still
 * refused on it.
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

    /**
     * The DuckDB (and Quack) configuration keys the confinement owns, in normalised form
     * (lower-case, {@code _ - . whitespace} removed) — the same normalisation
     * {@link JdbcUrlPolicy#normaliseKey} applies to any property name. A caller-supplied value for
     * any of these would pre-empt or undo the confinement (duckdb-java copies URL options into a
     * {@code Properties} map and applies them in hash order, resolving names case-insensitively),
     * so they are refused at {@link JdbcUrlPolicy#validate} time and stripped by the rewrite.
     */
    static final Set<String> DUCKDB_CONFINEMENT_KEYS =
            Set.of("enableexternalaccess", "alloweddirectories", "allowedpaths", "allowedconfigs", "lockconfiguration");

    /**
     * H2 URL prefixes naming a local database file; stripped (repeatedly) to reach the path.
     * Case-sensitive, exactly as H2's own grammar matches them (H2 2.x file systems: file, zip,
     * split, retry).
     */
    private static final Pattern H2_FILE_PREFIX = Pattern.compile("^(file:|zip:|split:(\\d+:)?|retry:)");

    /**
     * H2 prefixes that do NOT name a disk file: in-memory databases, the memory-only file systems
     * and the remote protocols. Case-sensitive — H2 matches these exactly, so {@code MEM:} is an
     * ordinary (relative) file path, not an in-memory database.
     */
    private static final Pattern H2_NON_FILE = Pattern.compile("^(mem:|memFS:|memLZF:|nioMemFS:|nioMemLZF:|tcp:|ssl:)");

    /**
     * Any other {@code scheme:} prefix at the start of an H2 database name: H2 2.x has none, so it
     * cannot be resolved safely (it would be a working-directory-relative path or a file system
     * this code does not know). Rejected, never treated as a relative path.
     */
    private static final Pattern UNKNOWN_FILE_ENGINE_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9_.+-]*:");

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private static final String DERBY_PREFIX = "jdbc:derby:";

    private static final String HSQLDB_PREFIX = "jdbc:hsqldb:";

    /** HSQLDB network database URLs: the file lives on the server, not on this host. */
    private static final Pattern HSQLDB_REMOTE = Pattern.compile("^(hsql:|hsqls:|http:|https:)");

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

    /**
     * Append the confinement options to a plain DuckDB URL. Idempotent by construction: any
     * URL-supplied copy of a confinement key is stripped first, so exactly one copy of each
     * option — ours — survives, whatever case or separator spelling the input used.
     */
    static String confineDuckDb(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith(DUCKDB_R2_PREFIX) && Boolean.getBoolean(ALLOW_R2_PROPERTY)) {
            LOG.warn("DuckDB r2:// upload URL left unconfined ({}=true)", ALLOW_R2_PROPERTY);
            return url;
        }
        String base = stripDuckDbConfinementKeys(url);
        // No known root: still lock external access, with no allowed directories (fail closed).
        String dirs = allowedRoots().isEmpty() ? "" : ";allowed_directories=" + duckDbDirectoryList();
        if (dirs.isEmpty()) {
            LOG.warn(
                    "saiku.home is unset and {} is empty: DuckDB local file access is fully disabled",
                    ALLOWED_ROOTS_PROPERTY);
        }
        return base + ";enable_external_access=false" + dirs + ";" + LOCKED_MARKER;
    }

    /**
     * The {@code ;} / {@code &} separated option tokens of a DuckDB (or Quack) URL — everything
     * after the database path, which may itself contain {@code ?} in an {@code r2://} URI. Used by
     * the user-key rejection in {@link JdbcUrlPolicy} and by the confinement rewrite.
     */
    static List<String> duckDbOptionTokens(String url) {
        List<String> tokens = new ArrayList<>();
        // The scheme may be duckdb or quack; find the end of it rather than assuming a prefix.
        int bodyStart = url.indexOf(':', "jdbc:".length()) + 1;
        int optStart = indexOfAny(url, ';', '?', bodyStart);
        if (optStart < 0) {
            return tokens;
        }
        int i = optStart + 1;
        while (i <= url.length()) {
            int end = indexOfAny(url, ';', '&', i);
            if (end < 0) {
                end = url.length();
            }
            tokens.add(url.substring(i, end));
            if (end >= url.length()) {
                break;
            }
            i = end + 1;
        }
        return tokens;
    }

    /**
     * Refuse a user-supplied value for a confinement key. A value that is exactly the confined
     * default is benign — it is what the rewrite appends itself, so a confined URL stays valid
     * under {@link JdbcUrlPolicy#validate}. Anything else could pre-empt or undo the confinement
     * and is refused outright.
     */
    static void rejectConfinementValue(String normalisedKey, String rawKey, String rawValue) {
        if (isSafeConfinementValue(normalisedKey, rawValue)) {
            return;
        }
        LOG.warn("Rejected a user-supplied DuckDB confinement setting: it is managed by file-engine confinement");
        throw new IllegalArgumentException("Invalid datasource JDBC URL: connection property '" + rawKey.trim()
                + "' is managed by file-engine confinement and may not be set (opt out with -D"
                + CONFINEMENT_PROPERTY + "=false)");
    }

    private static boolean isSafeConfinementValue(String normalisedKey, String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String v = rawValue.trim();
        switch (normalisedKey) {
            case "enableexternalaccess":
                return v.equalsIgnoreCase("false");
            case "lockconfiguration":
                return v.equalsIgnoreCase("true");
            case "alloweddirectories":
                return v.equals(duckDbDirectoryList());
            default:
                return false; // allowed_paths / allowed_configs are never set by the confinement
        }
    }

    /**
     * Remove every {@code key=value} option of the DuckDB URL whose key is a confinement key
     * ({@link #DUCKDB_CONFINEMENT_KEYS}). Options are the {@code ;} / {@code &} separated tokens
     * after the database path; the path itself is left untouched.
     */
    private static String stripDuckDbConfinementKeys(String url) {
        int optStart = indexOfAny(url, ';', '?', DUCKDB_PREFIX.length());
        if (optStart < 0) {
            return url;
        }
        StringBuilder out = new StringBuilder(url.substring(0, optStart));
        int i = optStart + 1;
        char sep = url.charAt(optStart);
        while (i <= url.length()) {
            int end = indexOfAny(url, ';', '&', i);
            if (end < 0) {
                end = url.length();
            }
            String token = url.substring(i, end);
            if (!token.isEmpty() && !isConfinementKey(token)) {
                out.append(sep).append(token);
            }
            if (end >= url.length()) {
                break;
            }
            sep = url.charAt(end);
            i = end + 1;
        }
        return out.toString();
    }

    /** True when the token's key (before the first {@code =}) is one of {@link #DUCKDB_CONFINEMENT_KEYS}. */
    private static boolean isConfinementKey(String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String name = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("jdbc.")) { // Mondrian's forwarding prefix on a wrapper-level key
            name = name.substring("jdbc.".length());
        }
        return DUCKDB_CONFINEMENT_KEYS.contains(JdbcUrlPolicy.normaliseKey(name));
    }

    private static int indexOfAny(String s, char a, char b, int from) {
        for (int i = Math.max(from, 0); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == a || c == b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Rewrite the DuckDB {@code Jdbc=} value of a Mondrian wrapper. Mondrian's own parser
     * ({@code Util.parseConnectString}) reads the wrapper, so quoting is handled exactly as
     * Mondrian handles it (a quoted {@code Jdbc='jdbc:duckdb:...;k=v'} value, a duplicated key
     * where the last wins); the confined value is re-serialised with the one quoting helper every
     * writer of a Mondrian connect string uses. Wrappers that carry no DuckDB {@code Jdbc=} value
     * are returned unchanged.
     */
    private static String confineMondrianWrapper(String url) {
        mondrian.olap.Util.PropertyList props;
        try {
            props = JdbcUrlPolicy.parseMondrianConnectString(url);
        } catch (RuntimeException malformed) {
            return url; // validate() rejects a malformed wrapper on every open path
        }
        if (props == null) {
            return url;
        }
        // parseConnectString dedupes case-insensitively with the last value winning (exactly
        // Mondrian's semantics), so at most one Jdbc= pair survives — whatever its spelling.
        String value = props.get("Jdbc");
        if (value == null || !value.trim().toLowerCase(Locale.ROOT).startsWith(DUCKDB_PREFIX)) {
            return url;
        }
        String confined = confineDuckDb(value.trim());
        if (confined.equals(value)) {
            return url;
        }
        // Re-serialise the parsed properties with the one quoting helper every writer of a
        // Mondrian connect string uses, so the ';'-bearing confined URL stays ONE Jdbc= value.
        StringBuilder sb = new StringBuilder();
        for (mondrian.util.Pair<String, String> p : props) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            String v = "jdbc".equalsIgnoreCase(p.left) ? confined : p.right;
            sb.append(p.left).append('=').append(JdbcUrlPolicy.quoteMondrianValue(v));
        }
        int bodyStart = url.indexOf(':', "jdbc:".length()) + 1; // after jdbc:mondrian[4]:
        return url.substring(0, bodyStart) + sb;
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

    // ------------------------------------------------------------- file-path confinement

    /**
     * Refuse a file database outside the allowed roots, for every file engine whose URL names a
     * database file: {@code h2}, {@code sqlite}, {@code derby} and {@code hsqldb}. In-memory and
     * remote (network) URLs are not file URLs and pass.
     *
     * <p>Unconfined only when the operator opted out; when no root can be determined (a plain WAR
     * without {@code saiku.home}) it fails closed: the URL is rejected.
     *
     * @param scheme the lower-cased JDBC scheme ({@code jdbc:} already verified by the caller)
     */
    static void rejectFileEnginePathOutsideRoots(String url, String scheme) {
        if (!isEnabled() || url == null) {
            return;
        }
        String path;
        switch (scheme) {
            case "h2":
                path = h2FilePath(url);
                break;
            case "sqlite":
                path = sqliteFilePath(url);
                break;
            case "derby":
                path = derbyFilePath(url);
                break;
            case "hsqldb":
                path = hsqldbFilePath(url);
                break;
            default:
                return;
        }
        if (path == null) {
            return;
        }
        List<Path> roots = allowedRoots();
        String engine = scheme.equals("h2") ? "H2" : scheme;
        if (roots.isEmpty()) {
            // Fail closed: with no root to anchor against, "confined" would mean "anywhere".
            LOG.warn("Rejected {} file database: saiku.home is unset and {} is empty", engine, ALLOWED_ROOTS_PROPERTY);
            throw new IllegalArgumentException("Invalid datasource JDBC URL: " + engine + " file databases need"
                    + " the saiku.home system property (or -D" + ALLOWED_ROOTS_PROPERTY + ") to be set (or opt out"
                    + " with -D" + CONFINEMENT_PROPERTY + "=false)");
        }
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(roots);
        if (!guard.isAllowed(resolve(path))) {
            LOG.warn("Rejected {} database path outside the permitted roots", engine);
            throw new IllegalArgumentException("Invalid datasource JDBC URL: " + engine + " database files must live"
                    + " under saiku.home (extend with -D" + ALLOWED_ROOTS_PROPERTY + ", or opt out with -D"
                    + CONFINEMENT_PROPERTY + "=false)");
        }
    }

    /** The file path an H2 URL names, or {@code null} for in-memory / remote databases. */
    private static String h2FilePath(String h2Url) {
        String rest = h2Url.substring(h2Url.toLowerCase(Locale.ROOT).indexOf("jdbc:h2:") + "jdbc:h2:".length());
        // Case-sensitive, exactly as H2 matches them: MEM: is a file path, mem: is not.
        if (H2_NON_FILE.matcher(rest).find()) {
            return null;
        }
        Matcher prefix = H2_FILE_PREFIX.matcher(rest);
        while (prefix.find()) {
            rest = rest.substring(prefix.end());
            prefix = H2_FILE_PREFIX.matcher(rest);
        }
        if (UNKNOWN_FILE_ENGINE_PREFIX.matcher(rest).find()) {
            // Not a file-system prefix H2 2.x knows: it would resolve against the working
            // directory (or name an unknown file system) — refuse rather than guess.
            throw new IllegalArgumentException("Invalid datasource JDBC URL: unsupported H2 file-system prefix —"
                    + " H2 database files must live under saiku.home (extend with -D" + ALLOWED_ROOTS_PROPERTY
                    + ", or opt out with -D" + CONFINEMENT_PROPERTY + "=false)");
        }
        int cut = rest.indexOf(';');
        String path = (cut >= 0 ? rest.substring(0, cut) : rest).trim();
        int bang = path.indexOf('!'); // zip:archive.zip!/db
        if (bang >= 0) {
            path = path.substring(0, bang);
        }
        return path.isEmpty() ? null : path;
    }

    /** The file path an SQLite URL names, or {@code null} for in-memory / resource databases. */
    private static String sqliteFilePath(String url) {
        String rest = url.substring(url.toLowerCase(Locale.ROOT).indexOf(SQLITE_PREFIX) + SQLITE_PREFIX.length());
        if (rest.startsWith("file:")) {
            rest = rest.substring("file:".length());
            if (rest.startsWith("//")) {
                // A file: URI authority is not a Saiku-recognised path; the remainder (a
                // working-directory-relative name) is treated as an ordinary path below.
                rest = rest.substring(2);
            }
        }
        if (rest.startsWith(":memory:") || rest.startsWith(":resource:")) {
            return null;
        }
        int cut = indexOfAny(rest, '?', ';', 0);
        String path = (cut >= 0 ? rest.substring(0, cut) : rest).trim();
        return path.isEmpty() ? null : path;
    }

    /** The file path a Derby (embedded) URL names, or {@code null} for memory / remote databases. */
    private static String derbyFilePath(String url) {
        String rest = url.substring(url.toLowerCase(Locale.ROOT).indexOf(DERBY_PREFIX) + DERBY_PREFIX.length());
        if (rest.startsWith("//") || rest.startsWith("net:")) {
            return null; // network server — the database lives on that host
        }
        if (rest.startsWith("memory:") || rest.startsWith("classpath:")) {
            return null;
        }
        if (rest.startsWith("directory:")) {
            rest = rest.substring("directory:".length());
        } else if (rest.startsWith("jar:")) {
            rest = rest.substring("jar:".length());
            // Both spellings: jar:(<archive>)<db> and jar:<archive>!<db>
            if (rest.startsWith("(")) {
                int close = rest.indexOf(')');
                rest = close >= 0 ? rest.substring(1, close) : rest.substring(1);
            } else {
                int bang = rest.indexOf('!');
                rest = bang >= 0 ? rest.substring(0, bang) : rest;
            }
        }
        if (UNKNOWN_FILE_ENGINE_PREFIX.matcher(rest).find()) {
            throw new IllegalArgumentException("Invalid datasource JDBC URL: unsupported Derby sub-subprotocol —"
                    + " Derby database files must live under saiku.home (extend with -D" + ALLOWED_ROOTS_PROPERTY
                    + ", or opt out with -D" + CONFINEMENT_PROPERTY + "=false)");
        }
        int cut = rest.indexOf(';');
        String path = (cut >= 0 ? rest.substring(0, cut) : rest).trim();
        return path.isEmpty() ? null : path;
    }

    /** The file path an HSQLDB URL names, or {@code null} for mem / res / remote databases. */
    private static String hsqldbFilePath(String url) {
        String rest = url.substring(url.toLowerCase(Locale.ROOT).indexOf(HSQLDB_PREFIX) + HSQLDB_PREFIX.length());
        if (rest.startsWith("mem:")
                || rest.startsWith("res:")
                || HSQLDB_REMOTE.matcher(rest).find()) {
            return null;
        }
        if (rest.startsWith("file:")) {
            rest = rest.substring("file:".length());
        }
        if (UNKNOWN_FILE_ENGINE_PREFIX.matcher(rest).find()) {
            throw new IllegalArgumentException("Invalid datasource JDBC URL: unsupported HSQLDB database URL —"
                    + " HSQLDB database files must live under saiku.home (extend with -D" + ALLOWED_ROOTS_PROPERTY
                    + ", or opt out with -D" + CONFINEMENT_PROPERTY + "=false)");
        }
        int cut = rest.indexOf(';');
        String path = (cut >= 0 ? rest.substring(0, cut) : rest).trim();
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
