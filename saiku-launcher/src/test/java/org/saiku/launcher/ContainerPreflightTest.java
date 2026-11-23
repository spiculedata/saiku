/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#2462 — the pre-boot behaviour that lived in {@code docker/saiku-entrypoint} now runs in
 * the launcher, because the Chainguard JRE image has no shell to run a script in.
 *
 * <p>Pinned here: the fail-closed saiku-home checks (#1989 S2b), the {@code JAVA_OPTS} bridge for
 * {@code -D} properties (JVM flags cannot be applied once the JVM is running, so they are
 * reported), and the {@code SAIKU_MCP_URL} bridge.
 */
public class ContainerPreflightTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    @Test
    public void missingHomeIsFine() {
        Path home = tmp.getRoot().toPath().resolve("fresh");
        assertEquals(Optional.empty(), ContainerPreflight.checkHome(home));
    }

    @Test
    public void emptyWritableHomeIsFine() {
        assertEquals(
                Optional.empty(), ContainerPreflight.checkHome(tmp.getRoot().toPath()));
    }

    @Test
    public void homeWithReadableSecretKeyIsFine() throws Exception {
        Path conf = Files.createDirectories(tmp.getRoot().toPath().resolve("conf"));
        Files.writeString(conf.resolve("secret.key"), "k");
        assertEquals(
                Optional.empty(), ContainerPreflight.checkHome(tmp.getRoot().toPath()));
    }

    @Test
    public void unwritableHomeFailsClosedWithChownRemediation() {
        Assume.assumeFalse("root bypasses file permissions", isRoot());
        File dir = tmp.getRoot();
        Assume.assumeTrue(dir.setWritable(false));
        try {
            String message = ContainerPreflight.checkHome(dir.toPath()).orElseThrow();
            assertTrue(message, message.contains("is not writable"));
            assertTrue(message, message.contains(dir.getPath()));
            assertTrue(message, message.contains("10001:10001"));
        } finally {
            dir.setWritable(true);
        }
    }

    @Test
    public void unreadableSecretKeyFailsClosedBecauseBootingWouldRotateTheKey() throws Exception {
        Assume.assumeFalse("root bypasses file permissions", isRoot());
        Path conf = Files.createDirectories(tmp.getRoot().toPath().resolve("conf"));
        File key = Files.writeString(conf.resolve("secret.key"), "k").toFile();
        Assume.assumeTrue(key.setReadable(false));
        try {
            String message =
                    ContainerPreflight.checkHome(tmp.getRoot().toPath()).orElseThrow();
            assertTrue(message, message.contains("secret.key"));
            assertTrue(message, message.contains("not readable"));
            assertTrue(message, message.contains("rotate the AES key"));
            assertTrue(message, message.contains("chown -R 10001:10001"));
        } finally {
            key.setReadable(true);
        }
    }

    @Test
    public void homeOptionBeatsEnvAndEnvBeatsNothing() {
        Path option = Paths.get("/opt/explicit");
        assertEquals(option, ContainerPreflight.resolveHome(option, Map.of("SAIKU_HOME", "/from/env")));
        assertEquals(Paths.get("/from/env"), ContainerPreflight.resolveHome(null, Map.of("SAIKU_HOME", "/from/env")));
        assertNull(ContainerPreflight.resolveHome(null, Map.of()));
        assertNull(ContainerPreflight.resolveHome(null, Map.of("SAIKU_HOME", "  ")));
    }

    @Test
    public void javaOptsDefinesAreParsedAndJvmFlagsAreReportedNotApplied() {
        ContainerPreflight.JavaOpts opts =
                ContainerPreflight.parseJavaOpts("-Dsaiku.ai.ask.provider=anthropic -Xmx1g  -Dflag -XX:+UseZGC");
        assertEquals("anthropic", opts.systemProperties().get("saiku.ai.ask.provider"));
        assertEquals("", opts.systemProperties().get("flag"));
        assertEquals(2, opts.systemProperties().size());
        assertEquals(List.of("-Xmx1g", "-XX:+UseZGC"), opts.unsupported());
    }

    @Test
    public void javaOptsKeepsValuesWithSpacesWhenQuoted() {
        ContainerPreflight.JavaOpts opts = ContainerPreflight.parseJavaOpts("-Dgreeting=\"hello world\" -Dother='a b'");
        assertEquals("hello world", opts.systemProperties().get("greeting"));
        assertEquals("a b", opts.systemProperties().get("other"));
    }

    @Test
    public void blankJavaOptsIsNoOp() {
        assertTrue(ContainerPreflight.parseJavaOpts(null).systemProperties().isEmpty());
        assertTrue(ContainerPreflight.parseJavaOpts("   ").unsupported().isEmpty());
    }

    @Test
    public void bridgeSetsAbsentPropertiesButNeverOverridesAnExplicitOne() {
        Properties props = new Properties();
        props.setProperty("saiku.auth.trustedProxyHops", "3");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ContainerPreflight.applyJavaOpts(
                "-Dsaiku.auth.trustedProxyHops=1 -Dsaiku.other=x",
                props,
                new PrintStream(buf, true, StandardCharsets.UTF_8));
        assertEquals("3", props.getProperty("saiku.auth.trustedProxyHops"));
        assertEquals("x", props.getProperty("saiku.other"));
        assertEquals("", buf.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void unsupportedJavaOptsWarnsAndPointsAtJavaToolOptions() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ContainerPreflight.applyJavaOpts(
                "-Xmx1g", new Properties(), new PrintStream(buf, true, StandardCharsets.UTF_8));
        String warning = buf.toString(StandardCharsets.UTF_8);
        assertTrue(warning, warning.contains("-Xmx1g"));
        assertTrue(warning, warning.contains("JAVA_TOOL_OPTIONS"));
        assertTrue(warning, warning.contains("JDK_JAVA_OPTIONS"));
    }

    @Test
    public void mcpUrlEnvBecomesSystemPropertyUnlessAlreadySet() {
        Properties props = new Properties();
        ContainerPreflight.applyEnvProperties(Map.of("SAIKU_MCP_URL", "https://x/mcp"), props);
        assertEquals("https://x/mcp", props.getProperty("saiku.mcp.url"));

        Properties explicit = new Properties();
        explicit.setProperty("saiku.mcp.url", "https://explicit");
        ContainerPreflight.applyEnvProperties(Map.of("SAIKU_MCP_URL", "https://x/mcp"), explicit);
        assertEquals("https://explicit", explicit.getProperty("saiku.mcp.url"));

        Properties blank = new Properties();
        ContainerPreflight.applyEnvProperties(Map.of("SAIKU_MCP_URL", " "), blank);
        assertFalse(blank.containsKey("saiku.mcp.url"));
    }
}
