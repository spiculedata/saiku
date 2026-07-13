/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous install-count heartbeat.
 *
 * <p>Download and pull counts don't map to installs (Docker layer caching, CI, private mirrors), so
 * instead each running instance sends a tiny ping on startup and once a day. The collector (a
 * Cloudflare Worker + D1, see {@code telemetry/} in this repo) keeps one row per install and counts
 * distinct installs seen in the last 30 days.
 *
 * <p><b>Privacy.</b> The payload is a random per-install id + version + coarse platform — no data, no
 * hostnames, no datasource details. The collector stores the id only as a hash and never records the
 * client IP. The response carries the latest released version, so the ping doubles as an update
 * check. Everything here is best-effort: telemetry must never delay startup or affect Saiku's
 * behaviour, so every failure is swallowed.
 *
 * <p><b>Opt-out.</b> On by default; disabled by any of {@code SAIKU_TELEMETRY=off},
 * {@code DO_NOT_TRACK=1}, or {@code -Dsaiku.telemetry.enabled=false}. The endpoint is overridable via
 * {@code SAIKU_TELEMETRY_ENDPOINT} / {@code -Dsaiku.telemetry.endpoint}.
 */
public final class TelemetryService {

    private static final System.Logger LOG = System.getLogger(TelemetryService.class.getName());

    private static final String DEFAULT_ENDPOINT = "https://telemetry.saiku.bi/v1/check";
    private static final String EDITION = "ce";
    private static final String INSTANCE_ID_FILE = "instance-id";
    private static final Duration STARTUP_DELAY = Duration.ofSeconds(10);
    private static final Duration PING_INTERVAL = Duration.ofHours(24);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern LATEST = Pattern.compile("\"latest\"\\s*:\\s*\"([^\"]+)\"");

    private final Path home;
    private final String version;
    private final String endpoint;
    private final HttpClient http;

    private TelemetryService(Path home, String version, String endpoint) {
        this.home = home;
        this.version = version;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Start the anonymous heartbeat unless the operator has opted out. Never throws — a telemetry
     * problem must never stop the server from serving.
     *
     * @param saikuHome the resolved saiku-home directory (holds the persistent install id)
     * @param version the running version (may be null in dev/IDE runs)
     */
    public static void startIfEnabled(Path saikuHome, String version) {
        try {
            if (!isEnabled()) {
                LOG.log(System.Logger.Level.DEBUG, "Saiku telemetry disabled by configuration");
                return;
            }
            // No release version means this isn't a deployed instance — it's a dev/IDE run or the
            // integration-test harness booting a server. Those shouldn't count as installs, so skip.
            if (version == null || version.isBlank() || "dev".equalsIgnoreCase(version)) {
                LOG.log(System.Logger.Level.DEBUG, "Saiku telemetry skipped: no release version (dev/test run)");
                return;
            }
            TelemetryService svc = new TelemetryService(saikuHome, version, endpointFromConfig());
            svc.announce();
            svc.schedule();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG, "telemetry init skipped: " + t.getMessage());
        }
    }

    private void announce() {
        System.out.println("Saiku sends an anonymous daily ping (random install id + version, no data) to "
                + "count active installs and check for updates. Disable with SAIKU_TELEMETRY=off. "
                + "Details: https://github.com/spiculedata/saiku/tree/development/telemetry");
    }

    private void schedule() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "saiku-telemetry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::pingQuietly, STARTUP_DELAY.toSeconds(), PING_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void pingQuietly() {
        try {
            ping();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG, "telemetry ping failed: " + t.getMessage());
        }
    }

    private void ping() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .header("content-type", "application/json")
                .header("user-agent", "saiku/" + version)
                .POST(HttpRequest.BodyPublishers.ofString(payload()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200) {
            notifyIfNewer(res.body());
        }
    }

    private String payload() {
        return "{\"id\":\"" + esc(installId()) + "\",\"version\":\"" + esc(version) + "\",\"edition\":\"" + EDITION
                + "\",\"os\":\"" + osName() + "\",\"arch\":\"" + esc(System.getProperty("os.arch", ""))
                + "\",\"java\":\""
                + esc(javaMajor()) + "\"}";
    }

    private void notifyIfNewer(String responseBody) {
        Matcher m = LATEST.matcher(responseBody);
        if (m.find() && isNewer(m.group(1), version)) {
            System.out.println("A newer Saiku release is available: " + m.group(1) + " (you are on " + version + ").");
        }
    }

    /** Persistent, anonymous install id under saiku-home; created once, reused after. */
    private String installId() {
        Path idFile = home.resolve(INSTANCE_ID_FILE);
        try {
            if (Files.isRegularFile(idFile)) {
                String existing = Files.readString(idFile).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String id = UUID.randomUUID().toString();
            Files.createDirectories(home);
            Files.writeString(idFile, id);
            return id;
        } catch (IOException e) {
            // couldn't persist (e.g. read-only home) — fall back to an ephemeral id for this run
            return UUID.randomUUID().toString();
        }
    }

    // --- configuration ------------------------------------------------------

    static boolean isEnabled() {
        if (isTruthy(System.getenv("DO_NOT_TRACK"))) {
            return false;
        }
        String flag = System.getenv("SAIKU_TELEMETRY");
        if (flag != null && isDisableWord(flag)) {
            return false;
        }
        String prop = System.getProperty("saiku.telemetry.enabled");
        return prop == null || !"false".equalsIgnoreCase(prop.trim());
    }

    private static String endpointFromConfig() {
        String prop = System.getProperty("saiku.telemetry.endpoint");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("SAIKU_TELEMETRY_ENDPOINT");
        return env != null && !env.isBlank() ? env.trim() : DEFAULT_ENDPOINT;
    }

    private static boolean isTruthy(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    private static boolean isDisableWord(String s) {
        String v = s.trim().toLowerCase();
        return v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no") || v.equals("disabled");
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return "linux";
        }
        return "other";
    }

    private static String javaMajor() {
        String v = System.getProperty("java.version", "");
        if (v.startsWith("1.") && v.length() > 2) {
            return v.substring(2, 3); // legacy 1.8 -> 8
        }
        int dot = v.indexOf('.');
        return dot > 0 ? v.substring(0, dot) : v;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Loose semver compare; true when {@code a} is newer than {@code b}. Any doubt returns false. */
    private static boolean isNewer(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? digits(pa[i]) : 0;
            int y = i < pb.length ? digits(pb[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int digits(String s) {
        String d = s.replaceAll("\\D", "");
        try {
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
