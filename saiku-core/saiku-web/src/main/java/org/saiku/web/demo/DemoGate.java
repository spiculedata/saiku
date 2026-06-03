/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime holder for the demo email gate — bundles the (optional) provider and
 * the signing cookie, and answers the single question the filter / resource /
 * info endpoint all ask: <em>is the gate enabled?</em>
 *
 * <p>Enabled = demo mode is on AND a provider is configured. In production
 * (demo off) or when WorkOS keys are absent, {@link #isEnabled()} is false and
 * every collaborator no-ops — login works directly, exactly as today.
 *
 * <p>Wired as a singleton bean via the {@link #fromEnvironment()} factory; tests
 * construct it directly with a fake provider.
 */
public class DemoGate {

    private static final Logger log = LoggerFactory.getLogger(DemoGate.class);

    /** Default verified-cookie lifetime — a returning visitor skips the gate. */
    public static final long DEFAULT_TTL_DAYS = 30;

    private final boolean demoMode;
    private final DemoGateProvider provider; // null when unconfigured
    private final DemoGateCookie cookie;

    public DemoGate(boolean demoMode, DemoGateProvider provider, DemoGateCookie cookie) {
        this.demoMode = demoMode;
        this.provider = provider;
        this.cookie = cookie;
    }

    /** True only when demo mode is active AND a provider is configured. */
    public boolean isEnabled() {
        return demoMode && provider != null;
    }

    public DemoGateProvider provider() {
        return provider;
    }

    public DemoGateCookie cookie() {
        return cookie;
    }

    public String providerName() {
        return provider == null ? null : provider.name();
    }

    /**
     * Spring {@code factory-method}: build the gate from the runtime environment.
     * Reads {@code WORKOS_API_KEY} + {@code WORKOS_CLIENT_ID} (both required for
     * the WorkOS path — {@code /authenticate} needs the client id as well as the
     * secret), {@code SAIKU_DEMO_GATE_TTL_DAYS}, and the cookie secret via
     * {@link DemoGateSecret}.
     */
    public static DemoGate fromEnvironment() {
        boolean demo = isDemoMode();

        String apiKey = System.getenv("WORKOS_API_KEY");
        String clientId = System.getenv("WORKOS_CLIENT_ID");
        DemoGateProvider provider = null;
        if (notBlank(apiKey) && notBlank(clientId)) {
            provider = new WorkOsMagicAuthProvider(apiKey.trim(), clientId.trim());
        } else if (demo) {
            log.warn("Demo mode is on but WORKOS_API_KEY / WORKOS_CLIENT_ID are not both set — "
                    + "email gate DISABLED (login works directly). Set both to enable it.");
        }

        long ttlDays = parseLong(System.getenv("SAIKU_DEMO_GATE_TTL_DAYS"), DEFAULT_TTL_DAYS);
        String home = System.getProperty("saiku.home");
        Path homePath = notBlank(home) ? Paths.get(home) : null;
        String secret = DemoGateSecret.resolve(System.getenv("SAIKU_DEMO_GATE_SECRET"), homePath);
        DemoGateCookie cookie = new DemoGateCookie(secret, ttlDays * 24 * 60 * 60);

        DemoGate gate = new DemoGate(demo, provider, cookie);
        if (gate.isEnabled()) {
            log.info("Demo email gate ENABLED (provider=workos, cookie TTL {} days).", ttlDays);
        }
        return gate;
    }

    /**
     * Mirror of {@code SaikuLauncher.isDemoModeRequested()} so the gate agrees
     * with the launcher on whether demo mode is active: env {@code SAIKU_DEMO},
     * {@code -Dsaiku.demo}, or the {@code demo} Spring profile.
     */
    static boolean isDemoMode() {
        String env = System.getenv("SAIKU_DEMO");
        if (env != null && Boolean.parseBoolean(env.trim())) {
            return true;
        }
        if (Boolean.parseBoolean(System.getProperty("saiku.demo", "false"))) {
            return true;
        }
        String profiles = System.getProperty("spring.profiles.active", "");
        for (String p : profiles.split(",")) {
            if ("demo".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
