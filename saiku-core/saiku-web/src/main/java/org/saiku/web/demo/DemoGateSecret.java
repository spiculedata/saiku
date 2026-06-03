/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the HMAC key for {@link DemoGateCookie}.
 *
 * <p>Order of precedence:
 * <ol>
 *   <li>{@code SAIKU_DEMO_GATE_SECRET} env var (operator-supplied — survives
 *       redeploys, lets multiple nodes share a key).</li>
 *   <li>{@code <saiku-home>/demo-gate/secret} — read if present, otherwise
 *       generated (32 random bytes) and persisted on first boot. Mirrors the
 *       launcher's {@code stageDefaultDatasource} "generate-once" pattern.</li>
 *   <li>An ephemeral per-run secret if there's no home dir to persist to (e.g.
 *       a unit test) — gate markers won't survive a restart, which is fine for
 *       that case.</li>
 * </ol>
 */
public final class DemoGateSecret {

    private static final Logger log = LoggerFactory.getLogger(DemoGateSecret.class);

    private DemoGateSecret() {}

    public static String resolve(String envValue, Path saikuHome) {
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        if (saikuHome == null) {
            return generate();
        }
        Path file = saikuHome.resolve("demo-gate").resolve("secret");
        try {
            if (Files.exists(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            Files.createDirectories(file.getParent());
            String secret = generate();
            Files.writeString(file, secret, StandardCharsets.UTF_8);
            restrictPermissions(file);
            log.info("Generated demo-gate cookie secret at {}", file);
            return secret;
        } catch (IOException e) {
            log.warn("Could not persist demo-gate secret to {} — using an ephemeral secret this run", file, e);
            return generate();
        }
    }

    static String generate() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows) — best effort; the file still sits
            // under saiku-home which the operator already controls.
        }
    }
}
