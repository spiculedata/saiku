/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An {@link ExportDestination}'s admin-supplied configuration, as an immutable name→value map
 * (saiku#1987).
 *
 * <p>A config carries two kinds of value and the distinction is load-bearing:
 *
 * <ul>
 *   <li><b>plain settings</b> — a Drive folder id, an S3 bucket, a SharePoint site path. Fine to
 *       echo back through the admin API, stored in {@code export-destinations/<id>.json}.</li>
 *   <li><b>secrets</b> — a service-account key path, an OAuth refresh token. Stored <b>only</b> in the
 *       owner-read/write {@code export-destinations/secrets.json}, never merged into the plain
 *       settings file, and never returned by the admin API. {@link #toString()} and
 *       {@link #toRedactedString()} both mask them, so a stray log line or a {@code %s} in an
 *       exception message cannot leak one.</li>
 * </ul>
 *
 * <p>Values are stored trimmed; a blank value is treated as absent so an empty admin form field does
 * not read back as a "set" value.
 */
public final class ExportDestinationConfig {

    private final Map<String, String> settings;
    private final Map<String, String> secrets;

    private ExportDestinationConfig(Map<String, String> settings, Map<String, String> secrets) {
        this.settings = Map.copyOf(settings);
        this.secrets = Map.copyOf(secrets);
    }

    /** An empty config. */
    public static ExportDestinationConfig empty() {
        return new ExportDestinationConfig(Map.of(), Map.of());
    }

    /**
     * Build a config from raw maps. Null maps are treated as empty; null/blank values are dropped.
     * Used by the config store when reading from disk and by the admin API when accepting a PUT.
     */
    public static ExportDestinationConfig of(Map<String, String> settings, Map<String, String> secrets) {
        return new ExportDestinationConfig(clean(settings), clean(secrets));
    }

    /** Build a config from only non-secret settings. */
    public static ExportDestinationConfig ofSettings(Map<String, String> settings) {
        return new ExportDestinationConfig(clean(settings), Map.of());
    }

    private static Map<String, String> clean(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    out.put(k.trim(), v.trim());
                }
            });
        }
        return out;
    }

    /** A non-secret setting, or null when unset/blank. */
    public String get(String name) {
        return settings.get(name);
    }

    /** A secret value, or null when unset. Prefer not calling this outside the destination itself. */
    public String secret(String name) {
        return secrets.get(name);
    }

    /**
     * A non-secret setting, failing with a sanitized message when it is missing. The message names
     * the field only — never the value.
     */
    public String require(String name) throws ExportDeliveryException {
        String v = get(name);
        if (v == null) {
            throw new ExportDeliveryException("export destination is not configured: missing setting '" + name + "'");
        }
        return v;
    }

    /** A secret, failing with a sanitized message when it is missing. */
    public String requireSecret(String name) throws ExportDeliveryException {
        String v = secret(name);
        if (v == null) {
            throw new ExportDeliveryException(
                    "export destination is not configured: missing credential '" + name + "'");
        }
        return v;
    }

    /** A non-secret setting, or {@code fallback} when unset. */
    public String getOr(String name, String fallback) {
        String v = get(name);
        return v == null ? fallback : v;
    }

    /** The non-secret settings. Safe to return over the admin API. */
    public Map<String, String> settings() {
        return settings;
    }

    /**
     * The secret values. <b>Not</b> safe to return over an API — the admin resource uses
     * {@link #secretNames()} instead. Present so a destination implementation can read its own
     * credentials.
     */
    public Map<String, String> secrets() {
        return secrets;
    }

    /** The names of the secrets that are set, never their values. Safe to return over the admin API. */
    public Set<String> secretNames() {
        return new TreeSet<>(secrets.keySet());
    }

    /** True when nothing at all has been configured. */
    public boolean isEmpty() {
        return settings.isEmpty() && secrets.isEmpty();
    }

    /**
     * A redacted, log-safe rendering. Every secret value is replaced by {@code ***}. Use this — not
     * {@link #toString()} — in any log line. ({@link #toString()} is redacted too, so there is no
     * unsafe variant to reach for by accident.)
     */
    public String toRedactedString() {
        StringBuilder sb = new StringBuilder("ExportDestinationConfig[settings=")
                .append(settings)
                .append(", secrets=");
        if (secrets.isEmpty()) {
            sb.append("{}");
        } else {
            sb.append("{");
            boolean first = true;
            for (String k : new TreeSet<>(secrets.keySet())) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(k).append("=***");
            }
            sb.append("}");
        }
        return sb.append("]").toString();
    }

    @Override
    public String toString() {
        return toRedactedString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExportDestinationConfig other)) {
            return false;
        }
        return settings.equals(other.settings) && secrets.equals(other.secrets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {settings, secrets});
    }

    /** Convenience for tests and for the admin API's "is this configured?" probe. */
    public boolean has(String name) {
        return settings.containsKey(name);
    }
}
