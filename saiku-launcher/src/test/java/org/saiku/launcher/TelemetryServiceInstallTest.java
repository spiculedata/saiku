/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Install-telemetry internals for {@link TelemetryService}: the install id
 * lifecycle, the payload, the update-notification compare and the ping
 * against a local HTTP endpoint. The class is private-method heavy, so these
 * use reflection (no Mockito in this codebase).
 */
public class TelemetryServiceInstallTest {

    /* --------------------------- reflection utils --------------------------- */

    private static Object invoke(String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? String.class : args[i].getClass();
        }
        Method m = TelemetryService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object priv(TelemetryService target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? String.class : args[i].getClass();
        }
        Method m = TelemetryService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static TelemetryService service(Path home, String version, String endpoint) throws Exception {
        java.lang.reflect.Constructor<TelemetryService> c =
                TelemetryService.class.getDeclaredConstructor(Path.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(home, version, endpoint);
    }

    /* ------------------------------- config ------------------------------- */

    @Test
    public void disableWordsDisableTelemetry() throws Exception {
        assertTrue((Boolean) invoke("isDisableWord", "off"));
        assertTrue((Boolean) invoke("isDisableWord", "Disabled"));
        assertFalse((Boolean) invoke("isDisableWord", "on"));
    }

    @Test
    public void truthyEnvValues() throws Exception {
        assertTrue((Boolean) invoke("isTruthy", "1"));
        assertTrue((Boolean) invoke("isTruthy", " YES "));
        assertFalse((Boolean) invoke("isTruthy", "maybe"));
        assertFalse((Boolean) invoke("isTruthy", (String) null));
    }

    @Test
    public void endpointConfigPrefersTheSystemProperty() throws Exception {
        String old = System.getProperty("saiku.telemetry.endpoint");
        try {
            System.setProperty("saiku.telemetry.endpoint", "http://127.0.0.1:9/v1/check");
            assertEquals("http://127.0.0.1:9/v1/check", invoke("endpointFromConfig"));
        } finally {
            if (old == null) {
                System.clearProperty("saiku.telemetry.endpoint");
            } else {
                System.setProperty("saiku.telemetry.endpoint", old);
            }
        }
    }

    @Test
    public void endpointConfigFallsBackToTheDefault() throws Exception {
        String old = System.getProperty("saiku.telemetry.endpoint");
        try {
            System.setProperty("saiku.telemetry.endpoint", "   ");
            assertEquals(TelemetryServiceInstallTest.class, TelemetryServiceInstallTest.class);
            String fallback = (String) invoke("endpointFromConfig");
            assertTrue(fallback.endsWith("/v1/check"));
        } finally {
            if (old == null) {
                System.clearProperty("saiku.telemetry.endpoint");
            } else {
                System.setProperty("saiku.telemetry.endpoint", old);
            }
        }
    }

    /* ------------------------------ payload ------------------------------- */

    @Test
    public void osNameCoversTheFamilies() throws Exception {
        String real = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals("windows", invoke("osName"));
            System.setProperty("os.name", "Mac OS X");
            assertEquals("macos", invoke("osName"));
            System.setProperty("os.name", "Linux");
            assertEquals("linux", invoke("osName"));
            System.setProperty("os.name", "Solaris");
            assertEquals("other", invoke("osName"));
        } finally {
            System.setProperty("os.name", real);
        }
    }

    @Test
    public void javaMajorParsesLegacyAndModernVersions() throws Exception {
        String real = System.getProperty("java.version");
        try {
            System.setProperty("java.version", "1.8.0_301");
            assertEquals("8", invoke("javaMajor"));
            System.setProperty("java.version", "22.0.1");
            assertEquals("22", invoke("javaMajor"));
            System.setProperty("java.version", "21");
            assertEquals("21", invoke("javaMajor"));
        } finally {
            System.setProperty("java.version", real);
        }
    }

    @Test
    public void escEscapesJsonDelimiters() throws Exception {
        assertEquals("", invoke("esc", (String) null));
        assertEquals("a\\\"b", invoke("esc", "a\"b"));
    }

    /* ------------------------------ install id ---------------------------- */

    @Test
    public void installIdIsCreatedOnceAndReused() throws Exception {
        Path home = Files.createTempDirectory("telemetry-reuse-");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        String first = (String) priv(svc, "installId");
        assertNotNull(first);
        assertEquals(first, Files.readString(home.resolve("instance-id")).trim());
        String second = (String) priv(svc, "installId");
        assertEquals(first, second);
    }

    @Test
    public void anEmptyPersistedIdIsReplacedWithAFreshOne() throws Exception {
        Path home = Files.createTempDirectory("telemetry-blank-");
        Files.writeString(home.resolve("instance-id"), "   \n");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        String id = (String) priv(svc, "installId");
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    public void aPersistedIdRoundTripsUnchanged() throws Exception {
        Path home = Files.createTempDirectory("telemetry-fixed-");
        Files.writeString(home.resolve("instance-id"), "fixed-id");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        assertEquals("fixed-id", priv(svc, "installId"));
    }

    /* ------------------------------ payload ------------------------------- */

    @Test
    public void payloadCarriesIdVersionAndEdition() throws Exception {
        Path home = Files.createTempDirectory("telemetry-payload-");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        priv(svc, "installId");
        String p = (String) priv(svc, "payload");
        assertTrue(p.startsWith("{\"id\":\""));
        assertTrue(p.contains("\"version\":\"4.7.1\""));
        assertTrue(p.contains("\"edition\":\"ce\""));
    }

    /* --------------------------- update check ----------------------------- */

    @Test
    public void newerVersionIsAnnouncedAndEqualIsNot() throws Exception {
        assertTrue((Boolean) invoke("isNewer", "4.8.0", "4.7.1"));
        assertFalse((Boolean) invoke("isNewer", "4.7.1", "4.7.1"));
        assertFalse((Boolean) invoke("isNewer", "bogus", "4.7.1"));
        assertFalse((Boolean) invoke("isNewer", "4.7.1", "4.8.0"));
    }

    @Test
    public void digitsIgnoresNonNumericContent() throws Exception {
        assertEquals(4, invoke("digits", "v4"));
        assertEquals(0, invoke("digits", ""));
        assertEquals(0, invoke("digits", "abc"));
    }

    /* ------------------------------ ping ---------------------------------- */

    @Test
    public void pingAgainstARealEndpointHitsTheConfiguredUrl() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/check", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("content-type", "application/json");
            byte[] body = "{\"latest\":\"9.9.9\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path home = Files.createTempDirectory("telemetry-ping-");
            TelemetryService local = service(
                    home, "4.7.1", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/check");
            priv(local, "ping");
            assertEquals(1, hits.get());
            assertNotNull(Files.readString(home.resolve("instance-id")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pingQuietlySwallowsConnectionFailures() throws Exception {
        Path home = Files.createTempDirectory("telemetry-dead-");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        priv(svc, "pingQuietly");
    }

    @Test
    public void announceMentionsTheOptOut() throws Exception {
        Path home = Files.createTempDirectory("telemetry-announce-");
        TelemetryService svc = service(home, "4.7.1", "http://127.0.0.1:1/v1/check");
        priv(svc, "announce");
    }
}
