package org.saiku.launcher;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "saiku",
        mixinStandardHelpOptions = true,
        version = "saiku 3.17",
        description = "Saiku OLAP server.",
        subcommands = {SaikuLauncher.ServeCommand.class})
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
            Path saikuHome = (home != null ? home : Paths.get("saiku-home")).toAbsolutePath();
            Path dataDir = saikuHome.resolve("data");
            Path brandingDir = saikuHome.resolve("branding");
            Files.createDirectories(dataDir);
            Files.createDirectories(saikuHome.resolve("repository").resolve("data"));
            Files.createDirectories(saikuHome.resolve("logs"));
            Files.createDirectories(saikuHome.resolve("plugins"));
            Files.createDirectories(brandingDir);
            System.setProperty("saiku.home", saikuHome.toString());
            System.out.println("Saiku home: " + saikuHome);

            stageSeedAssets(dataDir);
            stageBrandingSample(brandingDir);
            stageDefaultDatasource(saikuHome);

            Path warPath = extractWar();

            Server server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setHost(host);
            connector.setPort(port);
            server.addConnector(connector);

            WebAppContext webapp = new WebAppContext();
            webapp.setContextPath(contextPath);
            webapp.setWar(warPath.toString());
            webapp.setExtractWAR(true);

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
            String base = "http://" + host + ":" + port + contextPath;
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
            String bar = "============================================================";
            System.out.println();
            System.out.println(bar);
            System.out.println("  SECURITY: default credentials (admin/admin) are active.");
            System.out.println("  Change them in saiku-webapp's users.properties before");
            System.out.println("  exposing this instance, or replace the in-memory auth");
            System.out.println("  config (applicationContext-spring-security-memory.xml)");
            System.out.println("  with LDAP / OAuth / SAML.");
            System.out.println("  Suppress this warning with -Dsaiku.security.acknowledged=true");
            System.out.println(bar);
            System.out.println();
        }

        private void stageSeedAssets(Path dataDir) throws Exception {
            Path schema = dataDir.resolve("FoodMart4.xml");
            if (!Files.exists(schema)) {
                try (InputStream in = SaikuLauncher.class.getResourceAsStream("/seed/FoodMart4.xml")) {
                    if (in != null) {
                        Files.copy(in, schema, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Seeded: " + schema);
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
        }

        /**
         * Stages the default {@code foodmart} H2 datasource definition under
         * {@code saiku-home/repository/data/unknown/datasources/foodmart.sds},
         * substituting {@code @SAIKU_HOME@} with the absolute saiku-home so
         * the JDBC URL is portable across machines. Skipped if the file already
         * exists — preserves user customisations on re-launch.
         */
        private void stageDefaultDatasource(Path saikuHome) throws Exception {
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

        private void stageBrandingSample(Path brandingDir) throws Exception {
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

        private Path extractWar() throws Exception {
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
