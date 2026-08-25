/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "saiku",
        mixinStandardHelpOptions = true,
        version = "saiku 3.17",
        description = "Saiku Semantic Layer server.",
        subcommands = {
            SaikuLauncher.ServeCommand.class,
            OssieExportCommand.class,
            SqlServeCommand.class,
            EvalCommand.class
        })
public class SaikuLauncher implements Callable<Integer> {

    public static void main(String[] args) {
        int code = new CommandLine(new SaikuLauncher()).execute(args.length == 0 ? new String[] {"serve"} : args);
        if (code != 0) {
            System.exit(code);
        }
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "serve", description = "Start the Saiku web server.")
    public static class ServeCommand implements Callable<Integer> {

        @Option(
                names = {"-p", "--port"},
                description = "HTTP port (default 8080).",
                defaultValue = "8080")
        int port;

        @Option(
                names = {"-h", "--host"},
                description = "Bind host (default 0.0.0.0).",
                defaultValue = "0.0.0.0")
        String host;

        @Option(
                names = {"--context"},
                description = "Context path (default /).",
                defaultValue = "/")
        String contextPath;

        @Option(
                names = {"--home"},
                description = "Saiku home directory (data, repository, logs). Default: ./saiku-home")
        Path home;

        @Override
        public Integer call() throws Exception {
            Server server;
            try {
                server = bootServer(port, host, contextPath, home);
            } catch (DefaultCredentialsException e) {
                // saiku#1153: clean refusal, no stack trace — the message tells
                // the operator exactly how to proceed. Non-zero exit so init
                // systems / CI see the failure.
                System.err.println(e.getMessage());
                return 2;
            }
            int actualPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            String base = "http://" + host + ":" + actualPort + contextPath;
            if (!base.endsWith("/")) base = base + "/";
            System.out.println("Saiku ready:");
            System.out.println("  Workspace : " + base + "ui/");
            System.out.println("  Admin     : " + base + "ui/admin");
            System.out.println("  REST API  : " + base + "rest/saiku/");
            printDefaultCredentialWarning();
            server.join();
            return 0;
        }

        /**
         * Boot the embedded Jetty server with a fully wired Saiku WAR. Returns
         * the running {@link Server} so callers can introspect the bound port
         * via {@code server.getConnectors()[0].getLocalPort()} and orchestrate
         * shutdown. Used by the integration test harness so it can hit a real
         * webapp without spawning a separate JVM.
         *
         * <p>The supplied port may be {@code 0} for an OS-assigned ephemeral
         * port — discoverable post-start via the connector.
         */
        public static Server bootServer(int port, String host, String contextPath, Path home) throws Exception {
            Path saikuHome = (home != null ? home : Paths.get("saiku-home")).toAbsolutePath();
            Path dataDir = saikuHome.resolve("data");
            Path brandingDir = saikuHome.resolve("branding");
            Files.createDirectories(dataDir);
            Files.createDirectories(saikuHome.resolve("repository").resolve("data"));
            Files.createDirectories(saikuHome.resolve("logs"));
            Files.createDirectories(saikuHome.resolve("plugins"));
            Files.createDirectories(brandingDir);
            System.setProperty("saiku.home", saikuHome.toString());
            // Expose the running fat-JAR's version so InfoResource can stamp
            // it into the DXT manifest + the eventual /info/version endpoint.
            // Implementation-Version comes from the shade-plugin manifest
            // transformer (see saiku-launcher/pom.xml). Null in IDE / unit
            // runs — fine, the resource falls back to "0.0.0".
            String pkgVersion = SaikuLauncher.class.getPackage().getImplementationVersion();
            if (pkgVersion != null && !pkgVersion.isBlank()) {
                System.setProperty("saiku.version", pkgVersion);
            }
            // saiku#897: enable the "demo" Spring profile when the
            // SAIKU_DEMO=true env var is set. The profile gates loading of
            // users-demo.properties (bob / krishna / smith) — production
            // deployments leave SAIKU_DEMO unset and only get admin/admin.
            // An explicit -Dspring.profiles.active=demo also works for
            // operators who don't want to rely on env vars; if BOTH are
            // set, the explicit -D wins (don't clobber it here).
            if (isDemoModeRequested() && System.getProperty("spring.profiles.active") == null) {
                System.setProperty("spring.profiles.active", "demo");
            }
            // saiku#1769: the launcher's demo switch is the SAIKU_DEMO env var, but the
            // webapp reads a SYSTEM PROPERTY (InfoResource -> System.getProperty("saiku.demo")),
            // which is what /info/capabilities reports as `demoMode` and what the UI keys its
            // demo affordances off (pre-filled credential, "Try the demo" panel). Without this
            // bridge SAIKU_DEMO=true seeds demo users and prints the admin/admin banner while
            // the UI still renders a production login — i.e. the advertised credential is
            // unreachable. An explicit -Dsaiku.demo always wins, same as the profile above.
            String demoFlag = resolveDemoModeProperty(isDemoModeRequested(), System.getProperty("saiku.demo"));
            if (demoFlag != null) {
                System.setProperty("saiku.demo", demoFlag);
            }
            // Demo dashboards ship KPI + chart tiles that hit /ai/query for
            // aggregated result values. Relax the AiPolicy default to
            // AGGREGATED in demo mode — see resolveDemoAiPolicyDefault for
            // the decision logic + rationale.
            String demoAiPolicy = resolveDemoAiPolicyDefault(
                    isDemoModeRequested(), System.getenv("SAIKU_AI_POLICY"), System.getProperty("ai.policy"));
            if (demoAiPolicy != null) {
                System.setProperty("ai.policy", demoAiPolicy);
            }
            System.out.println("Saiku home: " + saikuHome);

            stageSeedAssets(dataDir);
            stageBrandingSample(brandingDir);
            stageDefaultDatasource(saikuHome);
            // #1394 demos: TPC-DS + Flights Ossie datasources with H2 fixtures.
            // Auto-provisioned on first boot so a fresh container has three Ossie
            // datasources ready to poke at via /ai/ossie/models. Idempotent —
            // stageResource + stageOssieDemoDatasource both no-op when the target
            // exists, so operator edits survive container restarts.
            stageOssieDemoDatasources(saikuHome);
            // saiku#1245: in demo mode, also stage a "Welcome" dashboard
            // under /dashboards/ so a fresh demo container has something
            // ready-to-look-at at first login instead of an empty list.
            // The file is idempotent (stageResource only writes when
            // missing) so operator edits survive container restarts.
            if (isDemoModeActive()) {
                stageDemoDashboards(saikuHome);
            }

            Path warPath = extractWar();

            // saiku#1512: runtime admin-password override so operators can set the
            // admin password (or supply an external users.properties) WITHOUT
            // rebuilding the image. Precedence: SAIKU_ADMIN_PASSWORD env /
            // -Dsaiku.admin.password > <saiku-home>/users.properties > the WAR's
            // baked default. When an override is in effect, Spring Security reads it
            // via -Dsaiku.security.usersFile and the policy below checks THAT file.
            Path usersFile = resolveEffectiveUsersFile(saikuHome, warPath);

            // saiku#1153: refuse to serve in production while the shipped default
            // admin password is unchanged. Demo mode (SAIKU_DEMO=true) and an
            // explicit SAIKU_ALLOW_DEFAULT_ADMIN=true both opt out. Throws
            // DefaultCredentialsException, which the CLI turns into a clean exit.
            enforceDefaultCredentialPolicy(usersFile, warPath);

            Server server = new Server();
            // saiku#1165 audit-3: harden the HTTP transport.
            //  * ForwardedRequestCustomizer — honour X-Forwarded-Proto / -Host
            //    from a TLS-terminating reverse proxy so request.isSecure() is
            //    true on a forwarded HTTPS request. Without it, the Secure flag
            //    on the XSRF-TOKEN cookie is dropped behind a proxy. When the
            //    forwarded headers are ABSENT (direct HTTP, e.g. the IT harness)
            //    isSecure() stays false — no behaviour change for direct hits.
            //
            //    IMPORTANT: X-Forwarded-For is DELIBERATELY NOT trusted
            //    (setForwardedForHeader(null)). Saiku binds plain HTTP directly
            //    by default (no proxy assumed), and LoginRateLimiter / AuditLogger
            //    intentionally key off getRemoteAddr() and only honour
            //    X-Forwarded-For when saiku.auth.trustForwardedFor=true. If the
            //    customizer rewrote getRemoteAddr() from X-Forwarded-For, any
            //    client could spoof its IP per request and evade the login rate
            //    limit + forge the audit trail. So we restore only the scheme/host
            //    here and leave client-IP resolution to the existing controls.
            //  * sendServerVersion=false — suppress the "Server: Jetty/<ver>"
            //    banner (version disclosure / fingerprinting).
            //  * sendXPoweredBy=false — likewise drop X-Powered-By.
            HttpConfiguration httpConfig = new HttpConfiguration();
            ForwardedRequestCustomizer forwarded = new ForwardedRequestCustomizer();
            forwarded.setForwardedForHeader(null); // do not let X-Forwarded-For override getRemoteAddr()
            httpConfig.addCustomizer(forwarded);
            httpConfig.setSendServerVersion(false);
            httpConfig.setSendXPoweredBy(false);
            // Keep Jetty's default request-header size unless an operator tunes it.
            httpConfig.setRequestHeaderSize(
                    Integer.getInteger("saiku.http.requestHeaderSize", httpConfig.getRequestHeaderSize()));
            ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
            connector.setHost(host);
            connector.setPort(port);
            server.addConnector(connector);

            WebAppContext webapp = new WebAppContext();
            webapp.setContextPath(contextPath);
            webapp.setWar(warPath.toString());
            webapp.setExtractWAR(true);

            // Drop-in driver support: every *.jar in <saiku-home>/plugins joins the
            // webapp classpath, so operators can add JDBC drivers (Trino, Redshift,
            // ClickHouse, ...) without rebuilding the fat-JAR. Scanned once at boot;
            // comma-separated because Windows paths contain ':'.
            // TRUST MODEL: this is a local-operator-only affordance. Any jar dropped here
            // runs with full webapp privileges (it's on the servlet classpath), so the
            // plugins/ directory MUST be owner-writable only — never group/world-writable,
            // never fed from an untrusted or network-mounted location. Treat it exactly like
            // adding a jar to the server's own classpath, because that is what it is.
            Path pluginsDir = saikuHome.resolve("plugins");
            if (Files.isDirectory(pluginsDir)) {
                try (var jarPaths = Files.list(pluginsDir)) {
                    String extraClasspath = jarPaths.filter(p -> p.getFileName()
                                    .toString()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .endsWith(".jar"))
                            .map(p -> p.toAbsolutePath().toString())
                            .sorted()
                            .collect(java.util.stream.Collectors.joining(","));
                    if (!extraClasspath.isEmpty()) {
                        webapp.setExtraClasspath(extraClasspath);
                        System.out.println("Plugins on webapp classpath: " + extraClasspath);
                    }
                }
            }

            // saiku#1165 audit-3: global backstop on form-urlencoded request
            // bodies (the per-endpoint caps only covered specific resources).
            // Multipart/file uploads are bounded separately by the Jersey
            // servlet's <multipart-config> in web.xml. Default 2 MB; tunable.
            webapp.setMaxFormContentSize(Integer.getInteger("saiku.http.maxFormContentSize", 2_000_000));

            // Tier-1 auth hardening: JSESSIONID cookie attrs.
            //   HttpOnly = true   (always — no JS needs to read it)
            //   SameSite = Lax    (block CSRF via top-level nav from other origins,
            //                      but still allow normal GET navigation)
            //   Secure   = property-gated (default false, turn on when fronted by TLS)
            boolean secureCookie = Boolean.parseBoolean(System.getProperty("saiku.session.cookie.secure", "false"));
            var sessionHandler = webapp.getSessionHandler();
            var cookieConfig = sessionHandler.getSessionCookieConfig();
            cookieConfig.setHttpOnly(true);
            cookieConfig.setSecure(secureCookie);
            // SameSite support in Jetty 12 is exposed via an attribute, not a getter.
            cookieConfig.setAttribute("SameSite", HttpCookie.SameSite.LAX.getAttributeValue());
            // Also set it at the SessionHandler level so Jetty honours it in the
            // Set-Cookie it builds (some paths read the attribute map, others
            // read the handler property; belt-and-braces).
            sessionHandler.setSameSite(HttpCookie.SameSite.LAX);

            // Persist sessions to disk so JSESSIONID + Spring SecurityContext
            // survive saiku restarts. Without this, the in-memory session map
            // is wiped on every server restart and the SPA's first /rest/**
            // XHR returns 401, which the saiku-ui surfaces as the
            // "Session ended" modal. Max-inactive bumped to 7 days so an
            // idle browser tab doesn't get prompted to re-login every hour.
            File sessionsDir = saikuHome.resolve("sessions").toFile();
            sessionsDir.mkdirs();
            FileSessionDataStore sessionStore = new FileSessionDataStore();
            sessionStore.setStoreDir(sessionsDir);
            DefaultSessionCache sessionCache = new DefaultSessionCache(sessionHandler);
            sessionCache.setSessionDataStore(sessionStore);
            sessionHandler.setSessionCache(sessionCache);
            sessionHandler.setMaxInactiveInterval(7 * 24 * 60 * 60);

            server.setHandler(webapp);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception ignored) {
                }
            }));

            server.start();

            // Anonymous install-count heartbeat (opt-out; disable with SAIKU_TELEMETRY=off).
            // Best-effort and off-thread — never blocks or fails the server.
            TelemetryService.startIfEnabled(saikuHome, System.getProperty("saiku.version"));

            return server;
        }

        /**
         * The bundled WAR ships with the in-memory Spring Security config
         * (applicationContext-spring-security-memory.xml) backed by an
         * admin/admin seed entry in users.properties. Suitable for a
         * single-host demo, not for any deployment exposed to the network.
         *
         * <p>Emit a loud, ASCII-bordered warning every launch so it's visible
         * in log aggregation and screen sessions. Suppressable for CI / demo
         * by setting {@code -Dsaiku.security.acknowledged=true} — at which
         * point you take responsibility for whatever auth posture you've put
         * in front of Saiku (reverse proxy, SSO, replaced security XML, etc.).
         */
        private void printDefaultCredentialWarning() {
            if (Boolean.parseBoolean(System.getProperty("saiku.security.acknowledged", "false"))) {
                return;
            }
            // A rotated admin password (SAIKU_ADMIN_PASSWORD / external users.properties / rebuilt
            // WAR) is not a default-credentials situation — stay quiet outside demo mode.
            boolean adminIsDefault = Boolean.parseBoolean(System.getProperty("saiku.security.adminIsDefault", "true"));
            if (!isDemoModeActive() && !adminIsDefault) {
                return;
            }
            String bar = "============================================================";
            System.out.println();
            System.out.println(bar);
            if (isDemoModeActive()) {
                System.out.println("  SECURITY: 4 default credentials are active (DEMO MODE):");
                System.out.println("    admin/admin                (ROLE_USER, ROLE_ADMIN)");
                System.out.println("    bob/dylan                  (ROLE_USER)");
                System.out.println("    krishna/krish2341          (ROLE_USER)");
                System.out.println("    smith/pravah@001           (ROLE_USER)");
                System.out.println("  Demo accounts own pre-seeded saved queries in");
                System.out.println("  /homes/<user>/ — useful for tutorials, NOT for production.");
                System.out.println("  Drop demo mode by unsetting SAIKU_DEMO and removing");
                System.out.println("  -Dspring.profiles.active=demo. See saiku#897.");
                // saiku#1769: the admin row above is only true when the EFFECTIVE users file
                // still carries the shipped default. A <saiku-home>/users.properties written by
                // an earlier SAIKU_ADMIN_PASSWORD boot outranks the WAR default, so the banner
                // would otherwise advertise admin/admin while the real password is the rotated
                // one nobody remembers — the instance reads as broken rather than locked.
                if (!adminIsDefault) {
                    System.out.println();
                    System.out.println("  NOTE: admin/admin above is NOT in effect — an external");
                    System.out.println("  users.properties (or SAIKU_ADMIN_PASSWORD) has rotated the");
                    System.out.println("  admin password. Delete <saiku-home>/users.properties to");
                    System.out.println("  restore the demo credential, or sign in with the rotated one.");
                }
            } else {
                System.out.println("  SECURITY: default credentials (admin/admin) are active.");
            }
            System.out.println("  Set SAIKU_ADMIN_PASSWORD=<strong-password> to rotate the admin");
            System.out.println("  password before exposing this instance (no rebuild needed), or");
            System.out.println("  replace the in-memory auth (applicationContext-spring-security-");
            System.out.println("  memory.xml) with LDAP / OAuth / SAML.");
            System.out.println("  Suppress this warning with -Dsaiku.security.acknowledged=true");
            System.out.println(bar);
            System.out.println();
        }

        /* ------------------- saiku#1153: default-credential policy ----------------- */

        /**
         * The exact {@code admin} password token this build ships with after the
         * saiku#1154 bcrypt migration. MUST stay in sync with the {@code admin=}
         * row in {@code saiku-webapp/.../WEB-INF/users.properties}; if you
         * regenerate that hash, update this constant too (a bcrypt salt is random,
         * so the string changes every time it is re-encoded).
         */
        static final String SHIPPED_BCRYPT_ADMIN_DEFAULT =
                "{bcrypt}$2a$10$4lJwsvvv..UqK31JmBSoCOf7hYgKurOB2sEacVVIrqA97BXc4cFju";

        /** Thrown to refuse boot when the default admin password is still active. */
        static final class DefaultCredentialsException extends RuntimeException {
            DefaultCredentialsException(String message) {
                super(message);
            }
        }

        /**
         * True when {@code adminPropertyValue} (the raw {@code admin=} value from
         * users.properties, i.e. {@code <encodedpw>,ROLE_...}) is still the
         * shipped default — either the legacy {@code {noop}admin} or this build's
         * {@link #SHIPPED_BCRYPT_ADMIN_DEFAULT}. Any operator rotation produces a
         * different hash and returns false.
         */
        static boolean isDefaultAdminValue(String adminPropertyValue) {
            if (adminPropertyValue == null) return false;
            String enc = adminPropertyValue.split(",", 2)[0].trim();
            return enc.equals("{noop}admin") || enc.equals(SHIPPED_BCRYPT_ADMIN_DEFAULT);
        }

        /**
         * Read the effective {@code admin} row from the bundled WAR's
         * users.properties and report whether it is still the shipped default.
         * Fails open (returns false) on any read error — a refusal must be a
         * deliberate, confident decision, never a side effect of a parse glitch.
         */
        static boolean adminPasswordIsDefault(Path warPath) {
            try (ZipFile zip = new ZipFile(warPath.toFile())) {
                ZipEntry e = zip.getEntry("WEB-INF/users.properties");
                if (e == null) return false;
                Properties p = new Properties();
                try (InputStream in = zip.getInputStream(e)) {
                    p.load(in);
                }
                return isDefaultAdminValue(p.getProperty("admin"));
            } catch (IOException ex) {
                return false;
            }
        }

        /** Pure policy decision: refuse only when the default is active AND we're
         *  not in demo mode AND the operator hasn't explicitly opted in. */
        static boolean shouldRefuse(boolean adminIsDefault, boolean demoMode, boolean allowOverride) {
            return adminIsDefault && !demoMode && !allowOverride;
        }

        /** True when {@code SAIKU_ALLOW_DEFAULT_ADMIN=true} (env) or
         *  {@code -Dsaiku.allowDefaultAdmin=true} (system property) is set. */
        static boolean allowDefaultAdmin() {
            String env = System.getenv("SAIKU_ALLOW_DEFAULT_ADMIN");
            if (env != null && Boolean.parseBoolean(env.trim())) return true;
            return Boolean.parseBoolean(System.getProperty("saiku.allowDefaultAdmin", "false"));
        }

        /**
         * saiku#1153: stop a production boot dead when the shipped default admin
         * password is unchanged. Demo mode and the explicit override opt out.
         *
         * @throws DefaultCredentialsException with an operator-facing fix-it
         *     message when the boot should be refused.
         */
        static void enforceDefaultCredentialPolicy(Path usersFile, Path warPath) {
            boolean isDefault = usersFile.equals(warPath)
                    ? adminPasswordIsDefault(warPath)
                    : isDefaultAdminValue(readAdminValue(usersFile));
            // Record it so the post-boot warning doesn't cry "default credentials" once rotated.
            System.setProperty("saiku.security.adminIsDefault", Boolean.toString(isDefault));
            if (!shouldRefuse(isDefault, isDemoModeRequested(), allowDefaultAdmin())) {
                return;
            }
            String bar = "============================================================";
            String msg = String.join(
                    System.lineSeparator(),
                    "",
                    bar,
                    "  FATAL: refusing to start — the default admin password is",
                    "  still in place (admin / admin). A network-exposed instance",
                    "  with default credentials is compromised within seconds.",
                    "",
                    "  Fix one of the following, then restart:",
                    "    * Set an admin password (no rebuild — recommended):",
                    "        SAIKU_ADMIN_PASSWORD=<a-strong-password>",
                    "    * Or rotate it in users.properties (bcrypt):",
                    "        htpasswd -nbBC 12 admin <newpassword>",
                    "    * Or replace the in-memory auth with LDAP / OAuth / SAML",
                    "      (applicationContext-spring-security-memory.xml).",
                    "",
                    "  To start anyway (NOT for a production / exposed host), set:",
                    "        SAIKU_ALLOW_DEFAULT_ADMIN=true",
                    "  or run the bundled demo:  SAIKU_DEMO=true",
                    bar,
                    "");
            throw new DefaultCredentialsException(msg);
        }

        /**
         * Resolve which users.properties Spring Security should authenticate against, and point it
         * there via {@code -Dsaiku.security.usersFile} when an override is active. Precedence:
         * {@code SAIKU_ADMIN_PASSWORD} / {@code -Dsaiku.admin.password} (hashed into an external
         * file) &gt; an existing {@code <saiku-home>/users.properties} &gt; the WAR's baked default
         * (returned as {@code warPath}). Never throws — falls back to the bundled file on any I/O error.
         */
        static Path resolveEffectiveUsersFile(Path saikuHome, Path warPath) {
            Path external = saikuHome.resolve("users.properties");
            String pw = System.getenv("SAIKU_ADMIN_PASSWORD");
            if (pw == null || pw.isBlank()) pw = System.getProperty("saiku.admin.password");
            try {
                if (pw != null && !pw.isBlank()) {
                    writeAdminUsersFile(external, pw.trim());
                    System.setProperty(
                            "saiku.security.usersFile", external.toUri().toString());
                    System.out.println("Admin password set from SAIKU_ADMIN_PASSWORD (" + external + ").");
                    return external;
                }
                if (Files.isRegularFile(external)) {
                    System.setProperty(
                            "saiku.security.usersFile", external.toUri().toString());
                    System.out.println("Using external users.properties (" + external + ").");
                    return external;
                }
            } catch (IOException e) {
                System.err.println("Could not write external users.properties (" + e.getMessage()
                        + "); falling back to the bundled default.");
            }
            return warPath;
        }

        /**
         * Write an external users.properties with a bcrypt {@code admin} row, preserving any other
         * (non-admin) rows already present so operator-added users survive a password change.
         */
        static void writeAdminUsersFile(Path file, String password) throws IOException {
            List<String> keep = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("admin=") || t.startsWith("admin ")) {
                        continue;
                    }
                    keep.add(line);
                }
            }
            String hash = new BCryptPasswordEncoder().encode(password);
            Files.createDirectories(file.getParent());
            List<String> out = new ArrayList<>();
            out.add("# Managed by Saiku: admin row set from SAIKU_ADMIN_PASSWORD. Add more users below.");
            out.add("admin={bcrypt}" + hash + ",ROLE_USER,ROLE_ADMIN");
            out.addAll(keep);
            Files.write(file, out);
            try {
                File f = file.toFile();
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);
            } catch (RuntimeException ignore) {
                // best-effort permission tightening — never fatal
            }
        }

        /** Read the {@code admin=} value from an external users.properties, or null on any error. */
        static String readAdminValue(Path file) {
            try (InputStream in = Files.newInputStream(file)) {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("admin");
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * True when the operator asked for demo mode either by env var
         * ({@code SAIKU_DEMO=true}) or explicit Spring profile activation
         * ({@code -Dspring.profiles.active=demo}). The two are honoured
         * symmetrically — the env var is the friendly switch the bundled
         * Docker image already sets; the {@code -D} is the override path
         * for operators who don't want the env-based affordance.
         */
        private static boolean isDemoModeRequested() {
            String env = System.getenv("SAIKU_DEMO");
            if (env != null && Boolean.parseBoolean(env.trim())) {
                return true;
            }
            String profiles = System.getProperty("spring.profiles.active", "");
            for (String p : profiles.split(",")) {
                if ("demo".equalsIgnoreCase(p.trim())) return true;
            }
            return false;
        }

        /** True when demo mode is currently in effect (post-bootstrap), which
         *  is equivalent to {@link #isDemoModeRequested()} once
         *  {@code bootServer} has run. Kept as a separate method so the
         *  intent at each call site is unambiguous. */
        private static boolean isDemoModeActive() {
            return isDemoModeRequested();
        }

        /**
         * Decide the {@code ai.policy} default under demo mode.
         *
         * <p>{@link org.saiku.service.olap.ai.AiPolicy} defaults to
         * {@code SCHEMA_ONLY} — the fail-closed safe posture for production. That's the wrong
         * default for the demo container: the welcome dashboard ships KPI + chart tiles that
         * call {@code /ai/query} for aggregated result values, and SCHEMA_ONLY blocks them with
         * an in-tile "AI policy 'schema-only' does not permit sending AGGREGATED_RESULT_VALUES"
         * error. Demo mode has no real PII and no leak risk (bundled FoodMart cube, H2), so we
         * relax the default to {@code aggregated} whenever demo mode is on.
         *
         * <p>Operators keep both escape hatches: an explicit {@code SAIKU_AI_POLICY} env var OR
         * an explicit {@code -Dai.policy=...} JVM flag ALWAYS wins over this defaulting — this
         * helper returns null when either is set. Empty / whitespace values count as unset so a
         * caller with {@code SAIKU_AI_POLICY=} in their env (e.g. Kubernetes ConfigMap with the
         * key present but blank) still gets the relaxed default.
         *
         * @param demoMode   whether demo mode is active (from {@link #isDemoModeRequested()})
         * @param envValue   current value of {@code SAIKU_AI_POLICY} env var (may be null)
         * @param propValue  current value of the {@code ai.policy} system property (may be null)
         * @return {@code "aggregated"} when demo mode should provide the relaxed default;
         *         {@code null} to leave {@code ai.policy} untouched
         */
        /**
         * saiku#1769 — decide the {@code saiku.demo} system property under demo mode.
         *
         * <p>The launcher's demo switch is the {@code SAIKU_DEMO} env var, but the webapp reads a
         * system property: {@code InfoResource} does {@code System.getProperty("saiku.demo")} and
         * publishes it as {@code demoMode} on {@code /info/capabilities}. The UI keys every demo
         * affordance off that flag — the pre-filled credential, the "Try the demo" panel, the
         * "Sign in as demo user" button. With no bridge, {@code SAIKU_DEMO=true} seeds the demo
         * users and prints the admin/admin banner while the UI renders a production login form,
         * so the advertised credential is effectively unreachable.
         *
         * <p>An explicit {@code -Dsaiku.demo=...} always wins, mirroring how the Spring profile
         * and {@code ai.policy} defaulting behave. Empty / whitespace counts as unset.
         *
         * @param demoMode  whether demo mode was requested (from {@link #isDemoModeRequested()})
         * @param propValue current value of the {@code saiku.demo} system property (may be null)
         * @return {@code "true"} when the launcher should set the property; {@code null} to leave it
         */
        static String resolveDemoModeProperty(boolean demoMode, String propValue) {
            if (!demoMode) return null;
            if (propValue != null && !propValue.isBlank()) return null;
            return "true";
        }

        static String resolveDemoAiPolicyDefault(boolean demoMode, String envValue, String propValue) {
            if (!demoMode) return null;
            if (envValue != null && !envValue.isBlank()) return null;
            if (propValue != null && !propValue.isBlank()) return null;
            return "aggregated";
        }

        /**
         * saiku#1662 — true when an already-materialised runtime schema differs
         * byte-for-byte from the bundled seed. Extracted + package-visible so the
         * drift detection is unit-testable without a live boot. Returns false when
         * either side is missing/empty (nothing to compare) so it never blocks boot.
         */
        static boolean runtimeSchemaIsStale(byte[] runtime, byte[] seed) {
            if (runtime == null || seed == null || runtime.length == 0 || seed.length == 0) {
                return false;
            }
            return !java.util.Arrays.equals(runtime, seed);
        }

        private static void stageSeedAssets(Path dataDir) throws Exception {
            Path schema = dataDir.resolve("FoodMart4.xml");
            if (!Files.exists(schema)) {
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/FoodMart4.xml")) {
                    if (in != null) {
                        Files.copy(in, schema, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Seeded: " + schema);
                    }
                }
            } else {
                // saiku#1662: an established home is never re-seeded (seed-if-absent
                // preserves user edits), so a runtime schema can silently fall
                // behind the bundled seed after a schema change — a dev home drifted
                // 2,000+ lines this way. Surface it on boot. WARN only: the file may
                // be an intentional local customisation, so we never overwrite it.
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/FoodMart4.xml")) {
                    if (in != null && runtimeSchemaIsStale(Files.readAllBytes(schema), in.readAllBytes())) {
                        System.out.println("WARNING: " + schema + " differs from the bundled seed schema"
                                + " (/seed/FoodMart4.xml). An established home is never re-seeded, so this"
                                + " file will not pick up schema changes automatically. If you did not"
                                + " customise it, delete it and relaunch to re-materialise the current seed"
                                + " (saiku#1662).");
                    }
                }
            }
            Path sql = dataDir.resolve("foodmart_h2.sql");
            if (!Files.exists(sql)) {
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/foodmart_h2.sql.zip")) {
                    if (in != null) {
                        try (ZipInputStream zis = new ZipInputStream(in)) {
                            ZipEntry e;
                            while ((e = zis.getNextEntry()) != null) {
                                if (e.getName().endsWith("foodmart_h2.sql")) {
                                    Files.copy(zis, sql, StandardCopyOption.REPLACE_EXISTING);
                                    System.out.println("Seeded: " + sql);
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // Bridge (many-to-many) demo: the Bank schema + its data load
            // into the same H2 database as FoodMart (distinct mm_* tables).
            // Database.loadBank() runs bank.sql and registers the datasource.
            stageResource("/seed/Bank.xml", dataDir.resolve("Bank.xml"));
            stageResource("/seed/bank.sql", dataDir.resolve("bank.sql"));
            // bank.lkml — the synthetic Looker model paired with Bank.xml.
            // Not loaded by Mondrian; staged here so the Looker→M4 migration
            // story has a discoverable fixture in <home>/data/ for anyone
            // running the demo locally.
            stageResource("/seed/bank.lkml", dataDir.resolve("bank.lkml"));
            // Bank.yaml — the M4 YAML round-trip of Bank.xml (mondrian-saiku
            // #112 + #130). Same schema, same cubes, same roles — just the
            // YAML representation produced by the M4 YAML converter. Staged
            // alongside Bank.xml so the round-trip story is verifiable in
            // <home>/data/.
            stageResource("/seed/Bank.yaml", dataDir.resolve("Bank.yaml"));
        }

        /** Copy a classpath seed resource to {@code target} if it is missing,
         *  preserving any user edits on relaunch. */
        private static void stageResource(String resource, Path target) throws Exception {
            if (Files.exists(target)) {
                return;
            }
            try (InputStream in = SaikuLauncher.class.getResourceAsStream(resource)) {
                if (in != null) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Seeded: " + target);
                }
            }
        }

        /**
         * Stages the default {@code foodmart} H2 datasource definition under
         * {@code saiku-home/repository/data/unknown/datasources/foodmart.sds},
         * substituting {@code @SAIKU_HOME@} with the absolute saiku-home so
         * the JDBC URL is portable across machines. Skipped if the file already
         * exists — preserves user customisations on re-launch.
         */
        private static void stageDefaultDatasource(Path saikuHome) throws Exception {
            Path dsDir = saikuHome
                    .resolve("repository")
                    .resolve("data")
                    .resolve("unknown")
                    .resolve("datasources");
            Files.createDirectories(dsDir);
            Path target = dsDir.resolve("foodmart.sds");
            if (Files.exists(target)) {
                return;
            }
            try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/foodmart.sds.template")) {
                if (in == null) {
                    return;
                }
                String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String resolved = body.replace("@SAIKU_HOME@", saikuHome.toString());
                Files.writeString(target, resolved, java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("Seeded: " + target);
            }
        }

        /**
         * Strip leading {@code -- comment} lines from a SQL statement so a header comment
         * doesn't cause the whole statement to be skipped. Idempotent — passes any statement
         * whose first non-blank line isn't a {@code --} comment through unchanged.
         */
        static String stripLeadingSqlComments(String stmt) {
            if (stmt == null) return "";
            String[] lines = stmt.split("\\r?\\n");
            int firstReal = 0;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty() || l.startsWith("--")) {
                    firstReal = i + 1;
                } else {
                    break;
                }
            }
            if (firstReal == 0) return stmt;
            StringBuilder sb = new StringBuilder();
            for (int i = firstReal; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        }

        /**
         * Stage the two Ossie demo datasources — TPC-DS and Flights (#1394 demo pass).
         *
         * <p>For each: stage the .ossie.yaml to {@code <home>/data/}, execute the seed
         * SQL against a fresh H2 database in the same directory, and materialise the
         * {@code .sds} descriptor. All three writes are idempotent — a repeat boot with
         * an existing H2 file, YAML, or .sds skips the corresponding step. Failures are
         * logged and the launcher continues so a broken fixture doesn't block the boot.
         */
        private static void stageOssieDemoDatasources(Path saikuHome) throws Exception {
            Path dataDir = saikuHome.resolve("data");
            Path dsDir = saikuHome
                    .resolve("repository")
                    .resolve("data")
                    .resolve("unknown")
                    .resolve("datasources");
            Files.createDirectories(dataDir);
            Files.createDirectories(dsDir);

            stageOneOssieDemo(saikuHome, dataDir, dsDir, "tpcds", "TPCDS");
            stageOneOssieDemo(saikuHome, dataDir, dsDir, "flights", "Flights");
        }

        /**
         * Stage one Ossie demo dataset: YAML + H2 fixture + .sds descriptor.
         *
         * @param slug   filename slug — matches {@code <slug>.ossie.yaml},
         *               {@code <slug>-seed.sql}, {@code <slug>-ossie.sds.template} on
         *               the launcher's classpath.
         * @param dsName the datasource name written into the .sds; matches the value
         *               the discover endpoint exposes.
         */
        private static void stageOneOssieDemo(Path saikuHome, Path dataDir, Path dsDir, String slug, String dsName)
                throws Exception {
            // 1. YAML — copy from classpath to <home>/data/.
            stageResource("/seed/" + slug + ".ossie.yaml", dataDir.resolve(slug + ".ossie.yaml"));

            // 2. H2 fixture — one .mv.db per demo. Skip if it already exists (idempotent).
            Path h2File = dataDir.resolve(slug + ".mv.db");
            if (!Files.exists(h2File)) {
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/" + slug + "-seed.sql")) {
                    if (in == null) {
                        System.out.println(
                                "Warning: no /seed/" + slug + "-seed.sql on classpath; skipping H2 seed for " + dsName);
                    } else {
                        String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        String jdbcUrl = "jdbc:h2:" + dataDir.resolve(slug) + ";MODE=PostgreSQL";
                        // Load H2 driver via reflection — the launcher's fat-JAR pins h2 via
                        // saiku-service, but we don't want a compile-time coupling from this
                        // package to h2.
                        Class.forName("org.h2.Driver");
                        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "");
                                java.sql.Statement st = c.createStatement()) {
                            for (String statement : sql.split(";\\s*\\r?\\n")) {
                                // Strip leading comment lines. A trimmed chunk that STARTS with
                                // `-- comment` but has an actual statement after may look like
                                // "-- header\nINSERT INTO ..." — the naive startsWith("--") skip
                                // would drop the whole INSERT.
                                String body = stripLeadingSqlComments(statement).trim();
                                if (body.isEmpty()) continue;
                                st.execute(body);
                            }
                        }
                        System.out.println("Seeded H2 fixture: " + h2File);
                    }
                } catch (Exception e) {
                    System.out.println(
                            "Warning: seeding " + dsName + " fixture failed (" + e.getMessage() + "); continuing.");
                }
            }

            // 3. .sds descriptor — substitute @SAIKU_HOME@ same way stageDefaultDatasource
            //    does for the foodmart datasource.
            Path sdsTarget = dsDir.resolve(slug + "-ossie.sds");
            if (!Files.exists(sdsTarget)) {
                try (InputStream in =
                        SaikuLauncher.class.getResourceAsStream("/seed/" + slug + "-ossie.sds.template")) {
                    if (in != null) {
                        String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        String resolved = body.replace("@SAIKU_HOME@", saikuHome.toString());
                        Files.writeString(sdsTarget, resolved, java.nio.charset.StandardCharsets.UTF_8);
                        System.out.println("Seeded: " + sdsTarget);
                    }
                }
            }
        }

        /**
         * Stage demo content under {@code <home>/repository/data/unknown/dashboards/}.
         * Only runs in demo mode (gated at the call site); idempotent because
         * {@link #stageResource} is a no-op when the target already exists, so
         * an operator who tweaks the welcome dashboard in the UI keeps their
         * edits across container restarts.
         *
         * <p>Lives next to the foodmart datasource staging so a single demo
         * boot lands cube + dashboards + branding sample together — a fresh
         * container has something to look at at first login.
         */
        private static void stageDemoDashboards(Path saikuHome) throws Exception {
            Path dashDir = saikuHome
                    .resolve("repository")
                    .resolve("data")
                    .resolve("unknown")
                    .resolve("dashboards");
            Files.createDirectories(dashDir);
            stageResource("/seed/demo/welcome.saikudash", dashDir.resolve("welcome.saikudash"));

            // Also seed a small FoodMart Sales saved query — the /ui/showcase/ SPA
            // embeds it via a public grant so the "Three surfaces, one cube" playground
            // has data to render on first launch of any demo instance. Same shape as the
            // dashboard: only lands when the file doesn't already exist, so operator
            // customisations survive re-launch.
            Path adminHome = saikuHome
                    .resolve("repository")
                    .resolve("data")
                    .resolve("unknown")
                    .resolve("homes")
                    .resolve("admin");
            Files.createDirectories(adminHome);
            stageResource("/seed/FoodMartTrend.saiku", adminHome.resolve("FoodMartTrend.saiku"));

            // Seed the "FoodMart Ops · Store Intelligence" App Builder app (saiku#1441) — the
            // reference .saikuapp the App Builder is measured against: full-bleed editorial shell,
            // icon nav rail, KPI row with inline sparklines, monthly-trend chart, a Movers table
            // with a real MoM store-sales-growth column (green/red conditional formatting), and the
            // in-app Ask assistant. Renders at /ui/apps/homes/admin/foodmart-ops.saikuapp/. Uses the
            // Store Sales Growth calculated member added to the Sales cube (FoodMart4.xml).
            // Idempotent — only lands when absent, so operator edits survive re-launch.
            stageResource("/seed/apps/foodmart-ops.saikuapp", adminHome.resolve("foodmart-ops.saikuapp"));

            // Public-grant the FoodMartTrend query so /ui/showcase/ can render its
            // <saiku-embed> widgets anonymously. Idempotent: if the operator already
            // manages embed-public.json we merge our entry in without touching theirs.
            grantFoodMartTrendPublic(saikuHome);

            // Seed the agent-skills catalogue (saiku#1426) with a working example. Operators
            // add their own alongside; this one demonstrates the frontmatter shape and gives
            // the DimSum widget something in the /ai/skills catalogue on a fresh install.
            Path skillsDir = saikuHome.resolve("skills");
            Files.createDirectories(skillsDir);
            stageResource("/seed/skills/weekly-foodmart-rollup.md", skillsDir.resolve("weekly-foodmart-rollup.md"));

            // Seed the agent-spaces catalogue (saiku#1440) with two personas: FoodMart Sales
            // Analyst (analytical, brief) + FoodMart Finance Ops (cautious, margin-focused). A
            // fresh launcher demo has personas ready to click without any operator authoring.
            Path spacesDir = saikuHome.resolve("agent-spaces");
            Files.createDirectories(spacesDir);
            stageResource(
                    "/seed/agent-spaces/foodmart-sales-analyst.json", spacesDir.resolve("foodmart-sales-analyst.json"));
            stageResource(
                    "/seed/agent-spaces/foodmart-finance-ops.json", spacesDir.resolve("foodmart-finance-ops.json"));

            // Seed the tile plugin catalogue (App Builder Phase 2, saiku#1441) with a working
            // example: a self-contained bar-chart tile that renders a record set under the host's
            // strict CSP (inline JS/CSS only, no remote refs). Admin-installed plugins land under
            // saiku-home/tile-plugins/<id>/; this one demonstrates the bundle shape + the
            // ready/resize postMessage protocol. Idempotent — operator edits survive re-launch.
            Path tilePluginDir = saikuHome.resolve("tile-plugins").resolve("records-bars");
            Files.createDirectories(tilePluginDir);
            stageResource("/seed/tile-plugins/records-bars/plugin.json", tilePluginDir.resolve("plugin.json"));
            stageResource("/seed/tile-plugins/records-bars/plugin.html", tilePluginDir.resolve("plugin.html"));
        }

        /**
         * Merge a {@code query:/homes/admin/FoodMartTrend.saiku} entry into
         * {@code saiku-home/embed-public.json} if not already present. Rewrites the file
         * only when the entry is missing — customer-managed grants pass through unchanged.
         */
        private static void grantFoodMartTrendPublic(Path saikuHome) throws IOException {
            Path registryFile = saikuHome.resolve("embed-public.json");
            String key = "query:/homes/admin/FoodMartTrend.saiku";
            String grantJson = "{\"resourceKind\":\"query\","
                    + "\"resourcePath\":\"/homes/admin/FoodMartTrend.saiku\","
                    + "\"grantedBy\":\"admin\","
                    + "\"ownerRolesSnapshot\":[\"ROLE_ADMIN\",\"ROLE_USER\"],"
                    + "\"grantedAt\":" + System.currentTimeMillis() + "}";
            String existing = Files.exists(registryFile)
                    ? Files.readString(registryFile, java.nio.charset.StandardCharsets.UTF_8)
                    : "{}";
            if (existing.contains("\"" + key + "\"")) {
                // Already granted — leave alone so a rotated `grantedAt` from a re-launch
                // doesn't stomp an operator-managed timestamp.
                return;
            }
            String merged;
            if (existing.trim().equals("{}") || existing.trim().isEmpty()) {
                merged = "{\"" + key + "\":" + grantJson + "}";
            } else {
                // Splice ",\"key\":<grantJson>" before the closing brace of the existing object.
                int close = existing.lastIndexOf('}');
                if (close < 0) {
                    // Registry file is malformed; leave it alone rather than overwrite.
                    return;
                }
                merged = existing.substring(0, close) + ",\"" + key + "\":" + grantJson + "}";
            }
            Files.writeString(registryFile, merged, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Granted public embed access: " + key);
        }

        private static void stageBrandingSample(Path brandingDir) throws Exception {
            Path sample = brandingDir.resolve("brand.css.sample");
            if (!Files.exists(sample)) {
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/brand.css.sample")) {
                    if (in != null) {
                        Files.copy(in, sample, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Seeded: " + sample);
                    }
                }
            }
        }

        private static Path extractWar() throws Exception {
            Path tmp = Files.createTempFile("saiku-", ".war");
            tmp.toFile().deleteOnExit();
            try (InputStream in = SaikuLauncher.class.getResourceAsStream("/webapp/saiku.war")) {
                if (in == null) {
                    Path local = Paths.get("saiku.war");
                    if (Files.exists(local)) {
                        return local.toAbsolutePath();
                    }
                    throw new IllegalStateException(
                            "Bundled WAR not found on classpath at /webapp/saiku.war and no saiku.war in cwd.");
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        }
    }
}
