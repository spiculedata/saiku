/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Pre-boot checks and environment bridging for {@code serve}.
 *
 * <p>saiku#2462: these used to live in {@code docker/saiku-entrypoint}, a Bash script. The
 * Chainguard JRE image has no shell, so the same behaviour now runs inside the launcher and
 * therefore also applies to the dist zip and to {@code java -jar} runs outside a container.
 */
final class ContainerPreflight {

    static final String UNSET_HOME_HINT = "10001:10001";

    private ContainerPreflight() {}

    /**
     * #1989 SEC review S2b (CWE-250): fail closed on a saiku-home the runtime user cannot use.
     *
     * <p>Docker only seeds image ownership into a fresh, empty volume; a pre-existing volume or
     * bind mount from an older root container keeps its root ownership. Booting on one would
     * leave {@code conf/secret.key} unreadable, so the key provider would silently generate a new
     * AES key and orphan every stored datasource password (and restore admin/admin in demo mode).
     * A genuinely fresh home (missing, or no secret.key yet) must still boot.
     *
     * @return the operator-facing failure message, or empty when it is safe to boot
     */
    static Optional<String> checkHome(Path home) {
        String who = runtimeUser();
        if (Files.exists(home) && !canWrite(home)) {
            return Optional.of(String.join(
                    "\n",
                    "FATAL: SAIKU_HOME '" + home + "' is not writable by " + who + ".",
                    "       This is usually a saiku-home from an older root container that has not",
                    "       been re-owned for the non-root runtime user (uid/gid " + UNSET_HOME_HINT + ").",
                    "       Bind mount:   sudo chown -R " + UNSET_HOME_HINT + " <host-dir>",
                    "       Named volume: docker run --rm -v <vol>:/h --user 0 \\",
                    "                       cgr.dev/chainguard/wolfi-base chown -R " + UNSET_HOME_HINT + " /h",
                    "       (this image has no shell or chown of its own, so use a throwaway image)"));
        }
        Path key = home.resolve("conf").resolve("secret.key");
        if (Files.exists(key) && !Files.isReadable(key)) {
            return Optional.of(String.join(
                    "\n",
                    "FATAL: '" + key + "' exists but is not readable by " + who + ".",
                    "       Booting would rotate the AES key and orphan every stored datasource password.",
                    "       Re-own the whole saiku-home to the runtime user: chown -R " + UNSET_HOME_HINT + " '" + home
                            + "'"));
        }
        return Optional.empty();
    }

    /** {@code --home} wins, then {@code SAIKU_HOME}; null leaves the launcher's own default. */
    static Path resolveHome(Path option, Map<String, String> env) {
        if (option != null) return option;
        String fromEnv = env.get("SAIKU_HOME");
        return fromEnv == null || fromEnv.isBlank() ? null : Paths.get(fromEnv.trim());
    }

    /** Result of reading {@code JAVA_OPTS}: properties the launcher can set, tokens it cannot. */
    record JavaOpts(Map<String, String> systemProperties, List<String> unsupported) {}

    /**
     * Split a {@code JAVA_OPTS} string. Only {@code -Dkey[=value]} can be honoured after the JVM
     * has started; every other token ({@code -Xmx}, {@code -XX:}, {@code -javaagent:}) is JVM
     * configuration and is returned as unsupported. Single and double quotes group a value that
     * contains spaces.
     */
    static JavaOpts parseJavaOpts(String javaOpts) {
        if (javaOpts == null || javaOpts.isBlank()) {
            return new JavaOpts(Collections.emptyMap(), Collections.emptyList());
        }
        Map<String, String> props = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        for (String token : tokenise(javaOpts)) {
            if (token.startsWith("-D") && token.length() > 2) {
                int eq = token.indexOf('=');
                if (eq < 0) {
                    props.put(token.substring(2), "");
                } else {
                    props.put(token.substring(2, eq), token.substring(eq + 1));
                }
            } else {
                unsupported.add(token);
            }
        }
        return new JavaOpts(props, unsupported);
    }

    private static List<String> tokenise(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : input.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) tokens.add(current.toString());
        return tokens;
    }

    /**
     * Honour the {@code -D} properties of {@code JAVA_OPTS} (the documented way to pass Saiku
     * settings to the old container) without overriding a property already set on the command
     * line. JVM flags cannot be applied by a running JVM, so they are reported once.
     */
    static void applyJavaOpts(String javaOpts, Properties target, PrintStream err) {
        JavaOpts parsed = parseJavaOpts(javaOpts);
        parsed.systemProperties().forEach((k, v) -> {
            if (target.getProperty(k) == null) target.setProperty(k, v);
        });
        if (!parsed.unsupported().isEmpty()) {
            err.println("WARN: JAVA_OPTS JVM options are not applied (the image has no shell to expand them): "
                    + String.join(" ", parsed.unsupported()));
            err.println("      Pass JVM flags through JAVA_TOOL_OPTIONS (replaces the image defaults) or");
            err.println("      JDK_JAVA_OPTIONS (added to them). -D properties in JAVA_OPTS are still honoured.");
        }
    }

    /** Env vars that surface as system properties the webapp reads (formerly forwarded by the entrypoint). */
    static void applyEnvProperties(Map<String, String> env, Properties target) {
        String mcpUrl = env.get("SAIKU_MCP_URL");
        if (mcpUrl != null && !mcpUrl.isBlank() && target.getProperty("saiku.mcp.url") == null) {
            target.setProperty("saiku.mcp.url", mcpUrl);
        }
    }

    /**
     * access(2) can say yes where a write is refused (bind mounts through a VM file share,
     * ACLs), so trust an actual write: create and delete a scratch file.
     */
    private static boolean canWrite(Path dir) {
        if (!Files.isWritable(dir)) return false;
        try {
            Files.deleteIfExists(Files.createTempFile(dir, ".saiku-preflight-", ".tmp"));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String runtimeUser() {
        String name = System.getProperty("user.name", "unknown");
        try {
            Path self = Paths.get("/proc/self");
            Object uid = Files.getAttribute(self, "unix:uid");
            Object gid = Files.getAttribute(self, "unix:gid");
            return "uid " + uid + ":" + gid + " (" + name + ")";
        } catch (Exception | LinkageError e) {
            return "user " + name;
        }
    }
}
