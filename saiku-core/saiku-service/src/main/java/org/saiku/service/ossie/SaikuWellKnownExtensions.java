/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import bi.saiku.ossie.model.CustomExtension;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Well-known extension keys under the {@code SAIKU} vendor namespace on OSI {@code
 * custom_extensions[]} entries (saiku#1409).
 *
 * <p>Ossie's OSI-spec {@code custom_extensions} field carries free-form vendor metadata. Saiku has
 * traditionally scattered its consumption of that blob (there's a dedicated {@code readPii()} and a
 * {@code readAggregationKind()} on the discover service, each hand-coding one field). This class
 * pulls the parsing together so every consumer sees the same typed view, and so operators have
 * exactly one place to look for what's supported.
 *
 * <p>Three well-knowns land in this slice; more can be added as needed. All live under
 * {@code vendor_name: SAIKU}:
 *
 * <ul>
 *   <li>{@code display} — caption / format / unit / hidden. Overlays field/metric presentation and
 *       gates whether the entry surfaces in the AI schema response at all.
 *   <li>{@code roles} — {@code allow} / {@code deny} role lists that filter which fields/metrics
 *       are visible to a given user. Enforcement lives with the Ossie RLS work (saiku#1393); this
 *       class just parses the annotation.
 *   <li>{@code pii} — extends the legacy {@code "pii": true} boolean into a graded {@code {level:
 *       "redact" | "mask" | "hash"}} shape. Backwards compatible: {@code true} keeps meaning
 *       {@code REDACT}.
 * </ul>
 *
 * <p>Everything else (unknown keys inside the SAIKU blob, other vendor names) round-trips
 * unchanged through {@link CustomExtensionDto} — this class is additive, never destructive.
 */
public final class SaikuWellKnownExtensions {

    /** The canonical vendor name for Saiku's well-known keys. */
    public static final String VENDOR_SAIKU = "SAIKU";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SaikuWellKnownExtensions() {}

    /**
     * Read the SAIKU vendor blob out of {@code extensions} and return a typed view. Returns an
     * empty {@link Parsed} record when no SAIKU extension is present, when the blob is empty, or
     * when the JSON is malformed.
     *
     * <p>Deliberately swallows parse errors — a bad extension blob mustn't break discover. The
     * discover service already logs at debug when {@link CustomExtensionDto} parsing fails; this
     * layer is even more of a best-effort read.
     */
    public static Parsed read(List<CustomExtension> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Parsed.EMPTY;
        }
        for (CustomExtension ext : extensions) {
            if (ext == null || !VENDOR_SAIKU.equals(ext.getVendorName())) continue;
            String data = ext.getData();
            if (data == null || data.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(data);
                if (node.isObject()) {
                    return parse(node);
                }
            } catch (IOException ignored) {
                // best-effort: skip and try the next SAIKU entry.
            }
        }
        return Parsed.EMPTY;
    }

    /**
     * Same as {@link #read(List)} but operates on the already-projected {@link CustomExtensionDto}
     * shape used downstream. Handy for consumers past the discover boundary.
     */
    public static Parsed readFromDtos(List<CustomExtensionDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return Parsed.EMPTY;
        }
        for (CustomExtensionDto dto : dtos) {
            if (dto == null || !VENDOR_SAIKU.equals(dto.getVendorName())) continue;
            JsonNode data = dto.getData();
            if (data == null || !data.isObject()) continue;
            return parse(data);
        }
        return Parsed.EMPTY;
    }

    private static Parsed parse(JsonNode node) {
        return new Parsed(readDisplay(node.get("display")), readRoles(node.get("roles")), readPii(node.get("pii")));
    }

    private static Display readDisplay(JsonNode d) {
        if (d == null || !d.isObject()) return null;
        String caption = readTextField(d, "caption");
        String format = readTextField(d, "format");
        String unit = readTextField(d, "unit");
        boolean hidden = d.has("hidden") && d.get("hidden").asBoolean(false);
        // A display block that only carries `hidden:false` (i.e. the default) is functionally
        // absent — return null so consumers don't allocate an empty overlay.
        if (caption == null && format == null && unit == null && !hidden) return null;
        return new Display(caption, format, unit, hidden);
    }

    private static Roles readRoles(JsonNode r) {
        if (r == null || !r.isObject()) return null;
        Set<String> allow = readStringSet(r.get("allow"));
        Set<String> deny = readStringSet(r.get("deny"));
        if (allow.isEmpty() && deny.isEmpty()) return null;
        return new Roles(allow, deny);
    }

    private static PiiLevel readPii(JsonNode p) {
        if (p == null || p.isNull()) return null;
        if (p.isBoolean()) {
            return p.asBoolean(false) ? PiiLevel.REDACT : null;
        }
        if (p.isObject()) {
            JsonNode level = p.get("level");
            if (level == null || !level.isTextual()) return null;
            String v = level.asText().toUpperCase(Locale.ROOT);
            return switch (v) {
                case "REDACT" -> PiiLevel.REDACT;
                case "MASK" -> PiiLevel.MASK;
                case "HASH" -> PiiLevel.HASH;
                default -> null;
            };
        }
        return null;
    }

    private static String readTextField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Set<String> readStringSet(JsonNode arr) {
        if (arr == null || !arr.isArray()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode e : arr) {
            if (!e.isTextual()) continue;
            String s = e.asText();
            if (s != null && !s.isBlank()) out.add(s);
        }
        return Collections.unmodifiableSet(out);
    }

    /** Typed view of the SAIKU vendor blob on one field / metric / dataset. All three well-knowns are optional. */
    public record Parsed(Display display, Roles roles, PiiLevel pii) {

        public static final Parsed EMPTY = new Parsed(null, null, null);

        /** True when every well-known is absent. Callers use this to short-circuit downstream overlays. */
        public boolean isEmpty() {
            return display == null && roles == null && pii == null;
        }
    }

    /**
     * {@code saiku.display} — cosmetic overrides that flow into the schema response so the workbench
     * (and any AI-schema consumer) renders the field/metric the way the operator intended.
     *
     * <p>{@code caption} overrides any Phase-3 {@code <datasource>.generated.json} rename for the
     * same object; the display extension is authoritative when both are present. {@code format} is a
     * number-format pattern (Java {@code DecimalFormat} syntax); {@code unit} is free-form ({@code
     * "USD"}, {@code "hours"}, {@code "%"}); {@code hidden} = true removes the entry from
     * agent-facing schema responses.
     */
    public record Display(String caption, String format, String unit, boolean hidden) {

        public boolean hasOverride() {
            return caption != null || format != null || unit != null || hidden;
        }
    }

    /**
     * {@code saiku.roles} — role allow/deny lists. Enforcement (filtering schema + query responses
     * against the current user's roles) lives with the Ossie RLS work in saiku#1393; this record is
     * the shared parse target so the annotation lands in the DTO layer today.
     *
     * <p>Semantics: {@code allow} empty = allow all; a non-empty {@code allow} requires the caller
     * to have at least one matching role. {@code deny} overrides {@code allow}.
     */
    public record Roles(Set<String> allow, Set<String> deny) {

        public Roles {
            allow = allow == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allow));
            deny = deny == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(deny));
        }

        /**
         * Whether a caller with the given {@code callerRoles} can see the annotated field/metric.
         * {@code null} or empty {@code callerRoles} is treated as "no roles" and matches only when
         * {@code allow} is empty and {@code deny} doesn't hit.
         */
        public boolean permits(Set<String> callerRoles) {
            Set<String> caller = callerRoles == null ? Set.of() : callerRoles;
            for (String d : deny) {
                if (caller.contains(d)) return false;
            }
            if (allow.isEmpty()) return true;
            for (String a : allow) {
                if (caller.contains(a)) return true;
            }
            return false;
        }
    }

    /**
     * {@code saiku.pii} — how sensitive values should be masked when they land in a response.
     *
     * <p>Legacy {@code "pii": true} maps to {@link #REDACT}. The graded form {@code {"level":
     * "mask"}} or {@code {"level": "hash"}} covers cases where the value must be present but
     * obscured. Enforcement of the level (the actual redaction) is a downstream concern — this
     * class just names the intent.
     */
    public enum PiiLevel {
        /** Value is null on the wire. Behaviour of the existing {@code pii: true} boolean. */
        REDACT,
        /** Value is replaced with a fixed mask token (e.g. {@code "***"}) preserving row shape. */
        MASK,
        /** Value is replaced with a deterministic hex prefix of a keyed hash, preserving joinability. */
        HASH
    }

    /** Convenience: are any well-knowns set on {@code extensions}? Runs in one pass. */
    public static boolean isAnyWellKnownPresent(List<CustomExtension> extensions) {
        return !Objects.equals(read(extensions), Parsed.EMPTY);
    }

    /** Convenience: same, over the DTO shape. */
    public static boolean isAnyWellKnownPresentDto(List<CustomExtensionDto> dtos) {
        return !readFromDtos(dtos).isEmpty();
    }

    /** Non-null empty list wrapping {@code extensions}, for callers that guard on null before iterating. */
    public static List<CustomExtension> emptyIfNull(List<CustomExtension> extensions) {
        return extensions == null ? new ArrayList<>() : extensions;
    }
}
