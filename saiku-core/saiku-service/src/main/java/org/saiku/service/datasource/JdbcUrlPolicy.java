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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection-time policy for every JDBC URL Saiku hands to {@link DriverManager}
 * (saiku#1902 / saiku#1903 / saiku#1904).
 *
 * <p>A datasource's JDBC URL reaches the driver from several directions — the admin REST add
 * path, an {@code .sds} descriptor read off the repository at boot or on {@code /discover/refresh},
 * the Ossie warehouse connection, the cube designer's preview and the schema generator. Modern JDBC
 * drivers treat certain URL parameters as <em>code</em>: pgjdbc and mssql-jdbc instantiate an
 * arbitrary class named in {@code socketFactory} / {@code sslfactory} (CVE-2022-21724 class),
 * Connector/J deserialises server-supplied bytes under {@code autoDeserialize} and loads
 * interceptor / plugin classes by name, H2 executes SQL from {@code INIT=RUNSCRIPT FROM '...'},
 * Calcite instantiates schema factories from a {@code model=} document, and a Mondrian
 * {@code DataSource=} is a raw JNDI lookup. With Spring, Calcite and several drivers on the
 * runtime classpath, any one of those is remote code execution on the server.
 *
 * <p>This class is therefore applied at the <b>chokepoint</b> — immediately before the driver is
 * touched — so it does not matter which path produced the URL. It enforces:
 *
 * <ul>
 *   <li>a strict <b>scheme allow-list</b> ({@link #BUILT_IN_SCHEMES}; extend with the
 *       {@value #ALLOWED_SCHEMES_PROPERTY} system property), so only drivers Saiku ships or that an
 *       operator has consciously dropped into {@code saiku-home/plugins/} are reachable;
 *   <li>a driver-independent <b>connection-property deny-list</b> for the class-loading,
 *       deserialisation, local-file and JNDI families, matched case-insensitively, after
 *       percent-decoding, with key-noise ({@code _ - . whitespace}) stripped so obfuscated
 *       spellings are caught;
 *   <li>the H2 executable tokens ({@code INIT=}, {@code RUNSCRIPT}, {@code CREATE ALIAS/TRIGGER/FORCE},
 *       {@code SHUTDOWN});
 *   <li>Mondrian wrapper rules: every nested {@code Jdbc=} sub-URL is validated recursively, and
 *       {@code DataSource=} must be a plain JNDI name, never an {@code ldap:}/{@code rmi:}-style
 *       URL.
 * </ul>
 *
 * <p>Rejections throw {@link IllegalArgumentException} with a short reason that never echoes the
 * URL (it may carry credentials). {@link #openConnection} and {@link #loadDriverClass} are the
 * preferred entry points: they validate and then do the work, so a call site cannot forget the
 * check.
 */
public final class JdbcUrlPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcUrlPolicy.class);

    /**
     * Comma-separated list of additional JDBC sub-schemes (the token between {@code jdbc:} and the
     * next {@code :}) an operator allows beyond {@link #BUILT_IN_SCHEMES}, e.g.
     * {@code -Dsaiku.jdbc.allowedSchemes=exa,sap}. The property deny-list still applies to them.
     */
    public static final String ALLOWED_SCHEMES_PROPERTY = "saiku.jdbc.allowedSchemes";

    private static final String PREFIX = "jdbc:";

    private JdbcUrlPolicy() {}

    /**
     * JDBC sub-schemes accepted without configuration. The first group is what the launcher /
     * webapp bundle, the second the olap4j wrappers Saiku itself speaks, the rest the warehouse and
     * lakehouse drivers an operator commonly drops into {@code saiku-home/plugins/}. Deliberately
     * absent: {@code calcite} / {@code avatica} — Saiku builds its own Calcite URLs internally for
     * Ossie and never accepts one from a datasource descriptor, because a Calcite {@code model=}
     * can name an arbitrary schema-factory class or nest any other JDBC URL.
     */
    static final Set<String> BUILT_IN_SCHEMES = Set.of(
            // bundled
            "h2",
            "hsqldb",
            "postgresql",
            "mysql",
            "mysql+srv",
            "mariadb",
            "jtds",
            "hive2",
            "quack",
            // olap4j wrappers Saiku speaks (jdbc:mondrian: is handled structurally, see below)
            "xmla",
            // common warehouse / lakehouse / embedded drivers
            "sqlserver",
            "oracle",
            "db2",
            "as400",
            "informix-sqli",
            "sybase",
            "firebirdsql",
            "sap",
            "derby",
            "sqlite",
            "duckdb",
            "trino",
            "presto",
            "spark",
            "databricks",
            "impala",
            "drill",
            "phoenix",
            "kylin",
            "pinot",
            "dremio",
            "athena",
            "awsathena",
            "redshift",
            "snowflake",
            "bigquery",
            "clickhouse",
            "ch",
            "vertica",
            "teradata",
            "netezza",
            "exa",
            "greenplum",
            "monetdb",
            "singlestore",
            "starrocks",
            "doris",
            "yugabytedb",
            "cockroachdb",
            "crate",
            "questdb",
            "timescaledb",
            "materialize",
            "firebolt",
            "elasticsearch",
            "opensearch",
            "ignite");

    /**
     * Connection-property keys that are never legitimate in a datasource URL, in normalised form
     * (lower-case, {@code _ - . whitespace} removed). Grouped by the primitive they hand an
     * attacker.
     */
    static final Set<String> DENIED_KEYS = Set.of(
            // arbitrary class instantiation (pgjdbc, mssql-jdbc, Connector/J, MariaDB)
            "sslhostnameverifier",
            "sslpasswordcallback",
            "authenticationpluginclassname",
            "authenticationplugins",
            "defaultauthenticationplugin",
            "queryinterceptors",
            "statementinterceptors",
            "exceptioninterceptors",
            "connectionlifecycleinterceptors",
            "propertiestransform",
            "clientinfoprovider",
            "loadbalancestrategy",
            "haloadbalancestrategy",
            "loadbalanceexceptionchecker",
            "serverconfigcachefactory",
            "parseinfocachefactory",
            "queryinfocachefactory",
            "accesstokencallbackclass",
            // deserialisation of server-controlled bytes / local-file exfiltration (Connector/J, MariaDB)
            "autodeserialize",
            "allowloadlocalinfile",
            "allowloadlocalinfileinpath",
            "allowurlinlocalinfile",
            "allowlocalinfile",
            // arbitrary file write (pgjdbc)
            "loggerfile",
            // H2 class hook
            "javaobjectserializer",
            // DuckDB / Quack: loading UNSIGNED native extensions, or signed ones from a
            // non-official repository. (autoload/autoinstall of the official, signed extension
            // set and extension_directory are ordinary hardening/config knobs and stay allowed —
            // saiku-cloud's synthetic jdbc:duckdb:r2:// upload URLs rely on httpfs autoloading.)
            "allowunsignedextensions",
            "customextensionrepository");

    /** Prefix matches, normalised: {@code socketFactory*}, {@code sslfactory*}, {@code java.naming.*}. */
    static final String[] DENIED_KEY_PREFIXES = {"socketfactory", "sslfactory", "javanaming"};

    /** Calcite / Avatica operands that name a document or class to instantiate. */
    static final Set<String> DENIED_CALCITE_KEYS = Set.of("model", "schemafactory", "schematype");

    /* H2 executable tokens. Matched case-insensitively against the (sub-)URL. */
    private static final Pattern H2_INIT = Pattern.compile("(?i)\\bINIT\\s*=");

    private static final Pattern H2_RUNSCRIPT = Pattern.compile("(?i)\\bRUNSCRIPT\\b");

    private static final Pattern H2_CREATE_EXEC = Pattern.compile("(?i)\\bCREATE\\s+(ALIAS|TRIGGER|FORCE)\\b");

    private static final Pattern H2_SHUTDOWN = Pattern.compile("(?i);\\s*SHUTDOWN\\b");

    /**
     * Keys of Mondrian's connect-string grammar ({@code RolapConnectionProperties} plus Saiku's own
     * {@code Mondrian=4} marker). A key opens the body or follows a {@code ;}; a value runs to the
     * next such key, which is what lets an inner {@code Jdbc=} URL carry its own {@code ;}-params.
     */
    private static final Pattern MONDRIAN_KEY = Pattern.compile("(?i)(?:^|;)\\s*(Provider|Jdbc|JdbcDrivers|JdbcUser|"
            + "JdbcPassword|Catalog|CatalogContent|CatalogName|DataSource|PoolNeeded|Role|UseContentChecksum|"
            + "DynamicSchemaProcessor|Locale|DataSourceChangeListener|Ignore|Instance|JdbcConnectionUuid|"
            + "PinSchemaTimeout|AggregateScanSchema|AggregateScanCatalog|UseSchemaPool|Mondrian)\\s*=");

    /** Legacy Saiku 3 form: {@code Mondrian=4; jdbc:mondrian:...}. Stripped before validation. */
    private static final Pattern LEGACY_MONDRIAN4_PREFIX = Pattern.compile("(?i)^\\s*Mondrian\\s*=\\s*4\\s*;\\s*");

    private static final Pattern URL_SCHEME_PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

    private static final Pattern SCHEME_TOKEN = Pattern.compile("[a-z0-9+.-]+");

    /**
     * saiku#1902 (SEC bypass): matches EVERY {@code key=} occurrence anywhere in the string, not
     * just the first one per {@code ;&?#}-delimited token. The old token split missed keys nested
     * in MySQL/MariaDB host-group and {@code address=(...)} forms
     * ({@code jdbc:mysql://(host=h,socketFactory=evil)/db}) and Teradata's comma-separated params
     * ({@code jdbc:teradata://h/DATABASE=x,LOGMECH=y,socketFactory=evil}) — a key run is a maximal
     * span of {@code [A-Za-z0-9_.-]} immediately before an {@code =} (optional whitespace between),
     * so it is found regardless of the {@code ( , / ; & ?} that precedes it. Values are never
     * treated as keys (only a run directly followed by {@code =} matches), and we reject only
     * known-dangerous key names, so a comma or paren inside a legitimate value can't produce one.
     */
    private static final Pattern PROPERTY_KEY = Pattern.compile("([A-Za-z0-9_.\\-]+)\\s*=");

    private static final Pattern KEY_NOISE = Pattern.compile("[\\s_.\\-'\"]");

    private static final int MAX_NESTING = 2;

    /**
     * Validate a JDBC URL or datasource {@code location=} value (which may be a Mondrian / XMLA
     * wrapper). {@code null} and blank input are accepted as "nothing to check" — the caller's
     * own null handling applies.
     *
     * @throws IllegalArgumentException when the URL is not permitted; the message never contains
     *     the URL itself
     */
    public static void validate(String jdbcUrlOrLocation) {
        if (jdbcUrlOrLocation == null) {
            return;
        }
        String url = jdbcUrlOrLocation.trim();
        if (url.isEmpty()) {
            return;
        }
        url = LEGACY_MONDRIAN4_PREFIX.matcher(url).replaceFirst("");
        checkUrl(url, 0);
    }

    /** Validate, then {@link DriverManager#getConnection(String, String, String)}. */
    public static Connection openConnection(String url, String user, String password) throws SQLException {
        validate(url);
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Validate the URL <em>and</em> the {@code info} property map, then
     * {@link DriverManager#getConnection(String, Properties)}. A driver merges {@code info} with
     * any properties in the URL, so a denied key smuggled through the map (e.g. a future caller
     * copying request-supplied properties) is exactly as dangerous as one in the URL. Today's
     * callers only put {@code user}/{@code password} here, which pass.
     */
    public static Connection openConnection(String url, Properties info) throws SQLException {
        validate(url);
        rejectDeniedInfoProperties(info);
        return DriverManager.getConnection(url, info);
    }

    /** Apply the connection-property deny-list to the keys of a {@link Properties} map. */
    static void rejectDeniedInfoProperties(Properties info) {
        if (info == null) {
            return;
        }
        for (String name : info.stringPropertyNames()) {
            String key = normaliseKey(name);
            if (!key.isEmpty()) {
                // false: the calcite model/schemaFactory operands are URL-only, never info keys.
                rejectIfDeniedKey(key, false);
            }
        }
    }

    /**
     * Load (and only then initialise) a JDBC driver class named by a datasource descriptor.
     *
     * <p>{@code Class.forName(name)} runs the static initialiser of <em>whatever</em> class is
     * named, so a descriptor-supplied name is a code-execution primitive independent of the URL.
     * This loads the class <b>without</b> initialisation first, refuses anything that is not a
     * {@link java.sql.Driver}, and initialises only after that check passed (initialisation is
     * what registers the driver with {@link DriverManager}, so the caller's contract is unchanged).
     *
     * @throws IllegalArgumentException when the class exists but is not a JDBC driver
     * @throws ClassNotFoundException when no such class is visible
     */
    public static Class<?> loadDriverClass(String className) throws ClassNotFoundException {
        return loadImplementation(className, java.sql.Driver.class);
    }

    /**
     * Load a class by name and initialise it only once it is known to implement {@code type}. Same
     * rationale as {@link #loadDriverClass}; used for the datasource / connection processor hooks.
     */
    public static <T> Class<? extends T> loadImplementation(String className, Class<T> type)
            throws ClassNotFoundException {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid datasource class name: empty");
        }
        String name = className.trim();
        Class<?> uninitialised = loadWithoutInitialising(name);
        if (!type.isAssignableFrom(uninitialised)) {
            LOG.warn("Rejected datasource class: not a {}", type.getSimpleName());
            throw new IllegalArgumentException("Invalid datasource class: not a " + type.getName() + " implementation");
        }
        ClassLoader loader = uninitialised.getClassLoader();
        Class<?> initialised = loader == null ? Class.forName(name) : Class.forName(name, true, loader);
        return initialised.asSubclass(type);
    }

    private static Class<?> loadWithoutInitialising(String name) throws ClassNotFoundException {
        ClassLoader own = JdbcUrlPolicy.class.getClassLoader();
        try {
            return Class.forName(name, false, own);
        } catch (ClassNotFoundException e) {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl != null && tccl != own) {
                return Class.forName(name, false, tccl);
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- internals

    private static void checkUrl(String url, int depth) {
        if (depth > MAX_NESTING) {
            throw reject("JDBC URL nesting is too deep");
        }
        rejectControlCharacters(url);
        String decoded = percentDecode(url);
        rejectControlCharacters(decoded);

        String scheme = schemeOf(decoded);
        boolean wrapper = "mondrian".equals(scheme) || "mondrian4".equals(scheme);

        // Coarsest gate first: an unknown driver is refused as such, whatever else the URL carries.
        if (!wrapper && !isAllowedScheme(scheme)) {
            throw reject("JDBC scheme '" + scheme + "' is not on the allowed list (extend it with -D"
                    + ALLOWED_SCHEMES_PROPERTY + "=scheme1,scheme2)");
        }

        // H2's executable tokens and the property deny-list are checked on BOTH spellings: what
        // the driver sees (raw) and what it would see after its own decoding.
        rejectDangerousH2Tokens(url);
        rejectDangerousH2Tokens(decoded);
        rejectDeniedProperties(url, scheme);
        rejectDeniedProperties(decoded, scheme);

        if (wrapper) {
            checkMondrianWrapper(url, depth);
        }
    }

    /** The token between {@code jdbc:} and the next {@code :}, lower-cased. */
    private static String schemeOf(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(PREFIX)) {
            throw reject("URL must start with jdbc:<scheme>:");
        }
        String rest = lower.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            throw reject("malformed JDBC scheme");
        }
        String scheme = rest.substring(0, colon);
        if (!SCHEME_TOKEN.matcher(scheme).matches()) {
            throw reject("malformed JDBC scheme");
        }
        return scheme;
    }

    private static boolean isAllowedScheme(String scheme) {
        if (BUILT_IN_SCHEMES.contains(scheme)) {
            return true;
        }
        return extraSchemes().contains(scheme);
    }

    private static Set<String> extraSchemes() {
        String extra = null;
        try {
            extra = System.getProperty(ALLOWED_SCHEMES_PROPERTY);
        } catch (SecurityException ignored) {
            // no property access -> built-ins only
        }
        Set<String> out = new HashSet<>();
        if (extra != null) {
            for (String s : extra.split(",")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static void rejectControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw reject("control characters are not permitted");
            }
        }
    }

    private static void rejectDangerousH2Tokens(String url) {
        // Only the H2 driver weaponises these tokens; gate on an H2 sub-URL being present anywhere
        // in the (possibly wrapped) string so a password that happens to contain "init=" on another
        // backend is not a false positive.
        if (!url.toLowerCase(Locale.ROOT).contains("jdbc:h2:")) {
            return;
        }
        if (H2_INIT.matcher(url).find()) {
            throw reject("H2 INIT= run-on-connect is not permitted");
        }
        if (H2_RUNSCRIPT.matcher(url).find()) {
            throw reject("H2 RUNSCRIPT is not permitted");
        }
        if (H2_CREATE_EXEC.matcher(url).find()) {
            throw reject("H2 CREATE ALIAS/TRIGGER/FORCE is not permitted");
        }
        if (H2_SHUTDOWN.matcher(url).find()) {
            throw reject("H2 SHUTDOWN is not permitted");
        }
    }

    private static void rejectDeniedProperties(String url, String scheme) {
        boolean calcite = "calcite".equals(scheme) || "avatica".equals(scheme);
        Matcher m = PROPERTY_KEY.matcher(url);
        while (m.find()) {
            String key = normaliseKey(m.group(1));
            if (key.isEmpty()) {
                continue;
            }
            rejectIfDeniedKey(key, calcite);
        }
    }

    /** The deny-list decision for a single normalised property key. */
    private static void rejectIfDeniedKey(String key, boolean calcite) {
        if (DENIED_KEYS.contains(key)) {
            throw reject("connection property '" + key + "' is not permitted");
        }
        for (String prefix : DENIED_KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                throw reject("connection property family '" + prefix + "*' is not permitted");
            }
        }
        if (calcite && DENIED_CALCITE_KEYS.contains(key)) {
            throw reject("Calcite model / schema-factory operands are not permitted");
        }
    }

    /** Lower-case and strip the characters drivers ignore or that an attacker can vary. */
    static String normaliseKey(String rawKey) {
        return KEY_NOISE.matcher(rawKey.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * Structural checks on a {@code jdbc:mondrian:} / {@code jdbc:mondrian4:} wrapper: every
     * {@code Jdbc=} occurrence is validated as a JDBC URL in its own right (Mondrian's property
     * list lets a later duplicate key win, so <em>all</em> of them are checked), every
     * {@code DataSource=} must be a plain JNDI name, and the class-instantiating hooks
     * {@code DynamicSchemaProcessor=} / {@code DataSourceChangeListener=} are refused outright —
     * Mondrian would {@code Class.forName}+instantiate whatever they name, and no legitimate Saiku
     * datasource sets them (saiku#1903, defence in depth).
     *
     * <p>Note: two other Mondrian keys also drive class-loading / inline content —
     * {@code JdbcDrivers=} (names the JDBC driver class, a legitimate and required part of a
     * Mondrian connect string) and {@code CatalogContent=} (an inline schema). These are left
     * permitted on purpose: a Mondrian wrapper is only ever authored as part of a datasource
     * descriptor or schema, which saiku#1903 + saiku#1904 make admin-only to write — so their
     * class-loading is admin-reachable-by-design, and blanket-denying {@code JdbcDrivers=} would
     * break every real Mondrian datasource.
     */
    private static void checkMondrianWrapper(String url, int depth) {
        String lower = url.toLowerCase(Locale.ROOT);
        int bodyStart = lower.startsWith("jdbc:mondrian4:") ? "jdbc:mondrian4:".length() : "jdbc:mondrian:".length();
        String body = url.substring(bodyStart);

        Map<String, List<String>> props = parseMondrianBody(body);

        // parseMondrianBody lower-cases its keys.
        if (props.containsKey("dynamicschemaprocessor")) {
            throw reject("Mondrian DynamicSchemaProcessor= (class instantiation) is not permitted in a datasource");
        }
        if (props.containsKey("datasourcechangelistener")) {
            throw reject("Mondrian DataSourceChangeListener= (class instantiation) is not permitted in a datasource");
        }

        for (String inner : props.getOrDefault("jdbc", List.of())) {
            String v = unquote(inner);
            if (!v.isEmpty()) {
                checkUrl(v, depth + 1);
            }
        }
        for (String ds : props.getOrDefault("datasource", List.of())) {
            String v = unquote(ds);
            Matcher m = URL_SCHEME_PREFIX.matcher(v);
            if (m.find()) {
                String jndiScheme = m.group(1).toLowerCase(Locale.ROOT);
                if (!"java".equals(jndiScheme) && !"osgi".equals(jndiScheme)) {
                    throw reject("Mondrian DataSource= must be a plain JNDI name, not a URL");
                }
            }
        }
    }

    private static Map<String, List<String>> parseMondrianBody(String body) {
        Matcher m = MONDRIAN_KEY.matcher(body);
        List<String> keys = new ArrayList<>();
        List<Integer> valueStarts = new ArrayList<>();
        List<Integer> matchStarts = new ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1).toLowerCase(Locale.ROOT));
            valueStarts.add(m.end());
            matchStarts.add(m.start());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            int end = (i + 1 < keys.size()) ? matchStarts.get(i + 1) : body.length();
            String value = body.substring(valueStarts.get(i), end).trim();
            out.computeIfAbsent(keys.get(i), k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    /**
     * Percent-decode repeatedly (bounded) so a doubly-encoded key cannot slip past the deny-list.
     * Unlike {@link java.net.URLDecoder} this leaves {@code +} alone — it is a legitimate scheme
     * character ({@code jdbc:mysql+srv:}) — and tolerates malformed escapes by copying them through.
     */
    static String percentDecode(String s) {
        String current = s;
        for (int round = 0; round < 3; round++) {
            String next = percentDecodeOnce(current);
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static String percentDecodeOnce(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '%' && i + 2 < bytes.length) {
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException reject(String reason) {
        // Never echo the URL: it may carry credentials, and the reason is all an operator needs.
        LOG.warn("Rejected datasource JDBC URL: {}", reason);
        return new IllegalArgumentException("Invalid datasource JDBC URL: " + reason);
    }
}
