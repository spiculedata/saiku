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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mondrian.olap.Util;
import mondrian.util.Pair;
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
 *       spellings are caught. Property names must be ASCII: drivers fold names with Unicode-aware
 *       case mapping, so a non-ASCII spelling could reach a driver as a denied key;
 *   <li>H2 connection settings are checked structurally: H2 applies each one by running
 *       {@code SET <key> <value>}, so a value is an SQL expression. Keys that run code when the
 *       database opens ({@code INIT}, {@code DATABASE_EVENT_LISTENER}, {@code JAVA_OBJECT_SERIALIZER})
 *       are refused whatever their value, escaped separators are refused, and every other value
 *       must be a plain token;
 *   <li>the H2 executable tokens ({@code INIT=}, {@code RUNSCRIPT}, {@code CREATE ALIAS/TRIGGER/FORCE},
 *       {@code SHUTDOWN});
 *   <li>Mondrian wrapper rules, applied to the properties Mondrian's own parser reads from the
 *       connect string: the nested {@code Jdbc=} URL is validated recursively, and
 *       {@code DataSource=} must be a plain JNDI name, never an {@code ldap:}/{@code rmi:}-style
 *       URL, and a {@code jdbc.}-prefixed key (which Mondrian forwards to the driver without the
 *       prefix) is checked under both spellings.
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
            // H2 class hooks
            "javaobjectserializer",
            "databaseeventlistener",
            // DuckDB: reads a local file and executes it as SQL when the connection opens
            "sessioninitsqlfile",
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

    /** SQL comments, removed before the token scan so they cannot split a keyword pair. */
    private static final Pattern SQL_COMMENT = Pattern.compile("(?s)/\\*.*?(\\*/|$)|--[^;]*");

    /**
     * H2 settings that run code when the database opens, whatever their value (normalised). Any
     * one of them is refused outright rather than by inspecting the value.
     */
    static final Set<String> H2_DENIED_SETTINGS =
            Set.of("init", "databaseeventlistener", "javaobjectserializer", "defaulttableengine");

    /** H2 settings H2 consumes itself rather than by running {@code SET}; their values are not SQL. */
    private static final Set<String> H2_CREDENTIAL_SETTINGS = Set.of("user", "password");

    /** A setting name; H2 has names with an inner space, such as {@code TIME ZONE}. */
    private static final Pattern H2_SETTING_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.]*( [A-Za-z0-9_.]+)*");

    /**
     * A plain setting value: no parentheses, operators or escapes, so no function call. A single
     * double-quoted identifier (for example {@code SCHEMA="PUBLIC"}) is also plain.
     */
    private static final Pattern H2_SETTING_VALUE = Pattern.compile("[A-Za-z0-9_.,:/+~ -]*|\"[A-Za-z0-9_.$ -]*\"");

    /** The prefix Mondrian strips before forwarding a connect-string property to the driver. */
    private static final String MONDRIAN_DRIVER_PREFIX = "jdbc.";

    /**
     * Keys of Mondrian's connect-string grammar ({@code RolapConnectionProperties} plus Saiku's own
     * {@code Mondrian=4} marker). Used by {@link FileEngineConfinement} to find where a wrapper's
     * {@code Jdbc=} value ends (saiku#2320).
     */
    static final Pattern MONDRIAN_KEY = Pattern.compile("(?i)(?:^|;)\\s*(Provider|Jdbc|JdbcDrivers|JdbcUser|"
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

    /**
     * A property name in a plain JDBC URL: a run that follows a parameter separator and ends at
     * {@code =}. Broader than {@link #PROPERTY_KEY} so a name containing non-ASCII characters is
     * seen whole. A run that follows {@code =} is inside a value and is not a name.
     */
    private static final Pattern NAME_RUN = Pattern.compile("[;?&,(/\\s'\"]([^\\s;&?#,()/:='\"]+)\\s*=");

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

    /**
     * Lock down the file/lakehouse engine in {@code url} (DuckDB external access and
     * configuration; see {@link FileEngineConfinement}). Applied by {@link #openConnection}; call
     * it on a URL that is handed to a driver some other way (Calcite model operands). Returns
     * {@code url} unchanged for other engines or when the operator opted out.
     */
    public static String confineFileEngines(String url) {
        return FileEngineConfinement.confine(url);
    }

    /** Validate, then {@link DriverManager#getConnection(String, String, String)}. */
    public static Connection openConnection(String url, String user, String password) throws SQLException {
        validate(url);
        return DriverManager.getConnection(confineFileEngines(url), user, password);
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
        rejectDeniedInfoProperties(url, info);
        return DriverManager.getConnection(url, info);
    }

    /**
     * Apply the connection-property deny-list to the keys of {@code info}. When {@code url}
     * reaches H2, each entry also gets the executable-token scan (raw and percent-decoded) and the
     * H2 setting rules, because H2 treats info entries exactly like URL settings (saiku#2321).
     * When {@code url} is a Mondrian wrapper, Mondrian merges {@code info} into its own property
     * list, so the entries are held to the wrapper rules too; only their {@code jdbc.}-prefixed
     * entries reach the driver.
     */
    static void rejectDeniedInfoProperties(String url, Properties info) {
        if (info == null) {
            return;
        }
        for (Map.Entry<Object, Object> e : info.entrySet()) {
            if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                throw reject("connection properties must be strings");
            }
        }
        String plain =
                url == null ? "" : LEGACY_MONDRIAN4_PREFIX.matcher(url.trim()).replaceFirst("");
        String lower = percentDecode(plain).toLowerCase(Locale.ROOT);
        boolean h2 = lower.contains("jdbc:h2:");
        boolean wrapper = isMondrianWrapper(lower);
        boolean h2Driver = false;
        if (wrapper) {
            h2Driver = isH2Url(parseMondrian(plain).get("Jdbc"));
            for (String name : info.stringPropertyNames()) {
                h2Driver |= "jdbc".equalsIgnoreCase(name.trim()) && isH2Url(info.getProperty(name));
            }
        }
        for (String name : info.stringPropertyNames()) {
            String value = info.getProperty(name, "");
            // false: the calcite model/schemaFactory operands are URL-only, never info keys.
            rejectIfDeniedRawKey(name, false);
            if (h2) {
                String pair = name + "=" + value;
                rejectH2Executable(pair);
                rejectH2Executable(percentDecode(pair));
            }
            if (wrapper) {
                checkMondrianEntry(name, value, h2Driver, 0);
            } else if (h2) {
                checkH2Setting(name, value);
            }
        }
    }

    /**
     * Quote a value for a Mondrian connect string ({@code Key=Value;...}). Values containing
     * {@code ;}, quotes or edge whitespace are single-quoted with embedded quotes doubled, so a
     * credential such as {@code p;Catalog=http://evil/x} stays one value (saiku#2321).
     */
    public static String quoteMondrianValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        boolean needsQuoting = value.indexOf(';') >= 0
                || value.indexOf('\'') >= 0
                || value.indexOf('"') >= 0
                || !value.equals(value.trim());
        return needsQuoting ? "'" + value.replace("'", "''") + "'" : value;
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
        if (!wrapper) {
            // A wrapper's names come from Mondrian's own parser (checkMondrianWrapper).
            rejectNonAsciiNames(url);
            rejectNonAsciiNames(decoded);
        }

        if ("h2".equals(scheme)) {
            checkH2Settings(url);
            // saiku#2320: an H2 file database must live under saiku.home (or an operator root).
            FileEngineConfinement.rejectH2PathOutsideRoots(url);
            FileEngineConfinement.rejectH2PathOutsideRoots(decoded);
        }
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
        rejectH2Executable(url);
    }

    /**
     * The H2 token scan, ungated. Defence in depth only: the structural setting rules
     * ({@link #checkH2Settings}, {@link #checkH2Setting}) are what keep SQL out of a connection.
     * Runs on the text as given and with SQL comments removed.
     */
    private static void rejectH2Executable(String text) {
        rejectH2Tokens(text);
        String uncommented = SQL_COMMENT.matcher(text).replaceAll(" ");
        if (!uncommented.equals(text)) {
            rejectH2Tokens(uncommented);
        }
    }

    private static void rejectH2Tokens(String url) {
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

    private static void rejectNonAsciiNames(String url) {
        Matcher m = NAME_RUN.matcher(url);
        while (m.find()) {
            rejectNonAsciiKey(m.group(1));
        }
    }

    private static void rejectDeniedProperties(String url, String scheme) {
        boolean calcite = "calcite".equals(scheme) || "avatica".equals(scheme);
        Matcher m = PROPERTY_KEY.matcher(url);
        while (m.find()) {
            rejectIfDeniedRawKey(m.group(1), calcite);
        }
    }

    /**
     * The deny-list decision for a property name as written. Names must be ASCII, and a name
     * carrying Mondrian's {@code jdbc.} forwarding prefix is also checked without it, because that
     * is the name the driver receives.
     */
    private static void rejectIfDeniedRawKey(String rawKey, boolean calcite) {
        rejectNonAsciiKey(rawKey);
        String key = normaliseKey(rawKey);
        if (!key.isEmpty()) {
            rejectIfDeniedKey(key, calcite);
        }
        String forwarded = stripDriverPrefix(rawKey);
        if (forwarded != null) {
            String stripped = normaliseKey(forwarded);
            if (!stripped.isEmpty()) {
                rejectIfDeniedKey(stripped, calcite);
            }
        }
    }

    /** The name without a leading {@code jdbc.} (any case, after trimming), or {@code null}. */
    private static String stripDriverPrefix(String rawKey) {
        String t = rawKey.trim();
        if (t.regionMatches(true, 0, MONDRIAN_DRIVER_PREFIX, 0, MONDRIAN_DRIVER_PREFIX.length())) {
            return t.substring(MONDRIAN_DRIVER_PREFIX.length());
        }
        return null;
    }

    private static void rejectNonAsciiKey(String rawKey) {
        if (!isAscii(rawKey)) {
            throw reject("connection property names must be ASCII");
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Structural check of an H2 URL's settings ({@code jdbc:h2:<name>;KEY=VALUE;...}). H2 splits
     * at the first {@code ;}; the database name before it is a path and is not inspected here.
     */
    private static void checkH2Settings(String h2Url) {
        int semi = h2Url.indexOf(';');
        if (semi < 0) {
            return;
        }
        String settings = h2Url.substring(semi + 1);
        if (!isAscii(settings)) {
            throw reject("H2 settings must be ASCII");
        }
        if (settings.indexOf('\\') >= 0) {
            throw reject("escape characters are not permitted in H2 settings");
        }
        for (String setting : settings.split(";")) {
            if (setting.isEmpty()) {
                continue;
            }
            int eq = setting.indexOf('=');
            if (eq < 0) {
                throw reject("malformed H2 setting");
            }
            checkH2Setting(setting.substring(0, eq), setting.substring(eq + 1));
        }
    }

    /**
     * One H2 setting, from the URL, the info map or a Mondrian {@code jdbc.} forward. Keys that run
     * code are refused whatever the value; any other value must be a plain token, because H2
     * evaluates it as an SQL expression.
     */
    private static void checkH2Setting(String rawKey, String value) {
        rejectNonAsciiKey(rawKey);
        String forwarded = stripDriverPrefix(rawKey);
        String name = forwarded != null ? forwarded : rawKey;
        String key = normaliseKey(name);
        if (H2_DENIED_SETTINGS.contains(key)) {
            throw reject(
                    "init".equals(key)
                            ? "H2 INIT= run-on-connect is not permitted"
                            : "H2 setting '" + key + "' is not permitted");
        }
        if (!H2_SETTING_KEY.matcher(name.trim()).matches()) {
            throw reject("malformed H2 setting");
        }
        if (H2_CREDENTIAL_SETTINGS.contains(key)) {
            return;
        }
        if (!H2_SETTING_VALUE.matcher(value).matches()) {
            throw reject("H2 setting values must be plain tokens");
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
     * Structural checks on a {@code jdbc:mondrian:} / {@code jdbc:mondrian4:} wrapper, applied to
     * the properties {@code Util.parseConnectString} yields, so quoting is read exactly as Mondrian
     * reads it: the {@code Jdbc=} value is validated as a JDBC URL in its own right, every
     * {@code DataSource=} must be a plain JNDI name, and the class-instantiating hooks
     * {@code DynamicSchemaProcessor=} / {@code DataSourceChangeListener=} are refused outright —
     * Mondrian would {@code Class.forName}+instantiate whatever they name, and no legitimate Saiku
     * datasource sets them (saiku#1903, defence in depth).
     *
     * <p>{@code Dialect=} is refused for the same reason: Mondrian initialises the named class before
     * checking its type, and Mondrian detects the dialect itself, so no datasource needs it.
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
        // Mondrian's own parser, so quoting and escaping are read exactly as Mondrian reads them.
        Util.PropertyList props = parseMondrian(url);
        boolean h2Driver = isH2Url(props.get("Jdbc"));
        for (Pair<String, String> p : props) {
            checkMondrianEntry(p.left, p.right == null ? "" : p.right, h2Driver, depth);
        }
    }

    private static Util.PropertyList parseMondrian(String wrapperUrl) {
        String lower = wrapperUrl.toLowerCase(Locale.ROOT);
        int bodyStart = lower.startsWith("jdbc:mondrian4:") ? "jdbc:mondrian4:".length() : "jdbc:mondrian:".length();
        try {
            return Util.parseConnectString(wrapperUrl.substring(bodyStart));
        } catch (RuntimeException e) {
            throw reject("malformed Mondrian connect string");
        }
    }

    private static boolean isMondrianWrapper(String lowerUrl) {
        return lowerUrl.startsWith("jdbc:mondrian:") || lowerUrl.startsWith("jdbc:mondrian4:");
    }

    private static boolean isH2Url(String url) {
        return url != null && percentDecode(url).trim().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
    }

    /**
     * One Mondrian property, from the parsed connect string or from an info map Mondrian merges
     * into it. Mondrian hands a {@code jdbc.<name>} property to the driver as {@code <name>}: the
     * deny-list sees both spellings, and an H2 driver also gets the setting rules.
     */
    private static void checkMondrianEntry(String name, String value, boolean h2Driver, int depth) {
        rejectIfDeniedRawKey(name, false);
        checkMondrianProperty(name.trim().toLowerCase(Locale.ROOT), value, depth);
        if (h2Driver && stripDriverPrefix(name) != null) {
            rejectH2Executable(name + "=" + value);
            checkH2Setting(name, value);
        }
    }

    /**
     * The wrapper rules for one Mondrian property (lower-cased key), from the connect string or
     * from an info map Mondrian merges into it.
     */
    private static void checkMondrianProperty(String key, String value, int depth) {
        String v = value.trim();
        switch (key) {
            case "dynamicschemaprocessor":
                throw reject("Mondrian DynamicSchemaProcessor= (class instantiation) is not permitted in a datasource");
            case "datasourcechangelistener":
                throw reject(
                        "Mondrian DataSourceChangeListener= (class instantiation) is not permitted in a datasource");
            case "dialect":
                throw reject("Mondrian Dialect= (class instantiation) is not permitted in a datasource");
            case "jdbc":
                if (!v.isEmpty()) {
                    checkUrl(v, depth + 1);
                }
                break;
            case "datasource":
                Matcher m = URL_SCHEME_PREFIX.matcher(v);
                if (m.find()) {
                    String jndiScheme = m.group(1).toLowerCase(Locale.ROOT);
                    if (!"java".equals(jndiScheme) && !"osgi".equals(jndiScheme)) {
                        throw reject("Mondrian DataSource= must be a plain JNDI name, not a URL");
                    }
                }
                break;
            default:
                break;
        }
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
