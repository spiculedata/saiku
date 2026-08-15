/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License. You may
 *   obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */
package org.saiku.service.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;

/**
 * Deterministic cache key for a {@link ThinQuery}. The key is a SHA-256 hex
 * digest over a canonical JSON serialization plus a cube-metadata version
 * fingerprint. Canonicalization:
 * <ul>
 *   <li>Map entries serialized in key order (Jackson
 *       {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}).</li>
 *   <li>Measure, hierarchy, and member lists sorted alphabetically by
 *       unique name in a pre-pass.</li>
 *   <li>Client-only display fields (query name, parameters that don't affect
 *       MDX, plugins, totals presentation) are omitted.</li>
 * </ul>
 */
public final class QueryCacheKey {

    private static final ObjectMapper MAPPER = buildMapper();

    /** The ThinSelection field holding the member list. */
    private static final String MEMBERS_FIELD = "members";

    private QueryCacheKey() {}

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        m.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return m;
    }

    /**
     * Hash a {@link ThinQuery} with the given cube-metadata version fingerprint.
     *
     * <p>Equivalent to {@link #of(ThinQuery, String, Collection)} with no roles. Retained
     * for callers that don't apply per-role schema masking; produces a byte-identical key
     * (the role-set is omitted from the canonical JSON when empty).
     */
    public static String of(ThinQuery query, String cubeVersion) {
        return of(query, cubeVersion, null);
    }

    /**
     * Hash a {@link ThinQuery} with the given cube-metadata version fingerprint and the
     * active session's Mondrian role-set.
     *
     * <p><b>The role-set must be part of the key (saiku#1114).</b> With Mondrian role-based
     * schema masking (the {@code <Role>} element) two users with different roles running the
     * SAME MDX get DIFFERENT results, but would otherwise share one cached cellset — a silent
     * cross-role data leak. Roles are de-duplicated and sorted so the key is independent of
     * role-binding order; an empty/absent role-set is omitted from the canonical JSON, so the
     * key stays byte-identical to the legacy two-arg form and existing single-role caches keep
     * hitting.
     */
    public static String of(ThinQuery query, String cubeVersion, Collection<String> roles) {
        if (query == null) {
            throw new IllegalArgumentException("query");
        }
        try {
            // Canonical JSON excludes client-only fields like `name` (random UUID),
            // `parameters` (resolved into MDX already), `plugins`, and `metadata`.
            CanonicalView view = new CanonicalView();
            view.type = query.getType() == null ? null : query.getType().name();
            view.mdx = query.getMdx();
            view.queryType = query.getQueryType();
            view.cube = query.getCube();
            view.queryModel = query.getQueryModel();
            view.cubeVersion = cubeVersion == null ? "" : cubeVersion;
            view.roles = canonicalRoles(roles);

            // Deliberately do NOT sort measure or hierarchy lists: their order is
            // part of the query (outer-to-inner axis nesting, measure column
            // order). Member selection order is semantically a set for HASHING —
            // so it is canonicalised on the serialised tree, never on the caller's
            // query. See sortMembersOnly() for why that distinction is load-bearing.
            JsonNode tree = MAPPER.valueToTree(view);
            sortMembersOnly(tree);

            byte[] json = MAPPER.writeValueAsBytes(tree);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json);
            return toHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build QueryCacheKey", e);
        }
    }

    /**
     * De-duplicate + sort role names so the key is deterministic regardless of role-binding
     * order ({@code [admin, reader]} and {@code [reader, admin]} hash the same). Null/blank
     * entries are dropped; an empty result is omitted from the JSON (see {@link CanonicalView}).
     */
    private static List<String> canonicalRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String r : roles) {
            if (r != null && !r.trim().isEmpty()) {
                sorted.add(r.trim());
            }
        }
        return new ArrayList<>(sorted);
    }

    /**
     * Cube-version fingerprint: cube coordinates plus the connection's metadata epoch
     * from {@link CubeMetadataVersions} (saiku#1483 — replaces the Phase 5 Task 5
     * placeholder that used coordinates alone).
     *
     * <p>The coordinates (connection|catalog|schema|cube) invalidate on reconfiguration
     * (catalog swap); the epoch invalidates on schema reload — every reload path funnels
     * through {@code AbstractConnectionManager.refreshConnection}, which bumps the epoch.
     * Without the epoch, a schema edit + refresh kept serving pre-edit cellsets because
     * the key never changed.
     */
    public static String cubeVersion(ThinQuery q) {
        if (q == null || q.getCube() == null) {
            return "";
        }
        SaikuCube c = q.getCube();
        StringBuilder sb = new StringBuilder();
        sb.append(nullSafe(c.getConnection())).append('|');
        sb.append(nullSafe(c.getCatalog())).append('|');
        sb.append(nullSafe(c.getSchema())).append('|');
        sb.append(nullSafe(c.getName())).append('|');
        sb.append(CubeMetadataVersions.epoch(c.getConnection()));
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Canonicalise member selections on the SERIALISED tree — never on the caller's query.
     *
     * <p>Member order is semantically a set for hashing, so it must be normalised or two identical
     * selections would miss each other in the cache. It is emphatically NOT a set for execution:
     * {@code ThinQueryService.execute} builds the coalescing key BEFORE calling
     * {@code executeInternalQuery}, which calls {@code updateQuery} → {@code Fat.convert} to
     * regenerate the MDX from the query model. saiku#1847: this method used to sort the member list
     * in place, so the alphabetised order became the order in the emitted MDX and the user got rows
     * back sorted by member name rather than in the order they selected them. Hashing something must
     * not change it.
     *
     * <p>Sorting the JSON keeps the key byte-identical to the previous implementation — same
     * comparator, same lowercase-by-unique-name ordering — so existing on-disk caches keep hitting.
     */
    private static void sortMembersOnly(JsonNode tree) {
        if (tree == null) {
            return;
        }
        if (tree.isObject()) {
            ObjectNode obj = (ObjectNode) tree;
            Iterator<String> names = obj.fieldNames();
            List<String> fields = new ArrayList<>();
            while (names.hasNext()) {
                fields.add(names.next());
            }
            for (String field : fields) {
                JsonNode child = obj.get(field);
                if (MEMBERS_FIELD.equals(field) && child != null && child.isArray()) {
                    obj.set(field, sortedByUniqueName((ArrayNode) child));
                } else {
                    sortMembersOnly(child);
                }
            }
            return;
        }
        if (tree.isArray()) {
            for (JsonNode child : tree) {
                sortMembersOnly(child);
            }
        }
    }

    /**
     * Order members by lowercased unique name, tie-broken on the exact name.
     *
     * <p>The tie-break is load-bearing, and its absence was a long-standing bug this rewrite
     * inherited. Sorting on the lowercased name alone leaves members whose names differ only by
     * case ({@code [Store].[A]} vs {@code [Store].[a]}) comparing EQUAL. Java's sort is stable, so
     * equal elements keep their input order — meaning the "canonical" form still depended on the
     * order the caller happened to send, and two identical selections hashed differently and missed
     * each other in the cache. Comparing the exact name second makes the order total, so
     * canonicalisation is genuinely canonical.
     *
     * <p>Only affects selections containing case-colliding member names; every other key is
     * unchanged.
     */
    private static ArrayNode sortedByUniqueName(ArrayNode members) {
        List<JsonNode> sorted = new ArrayList<>(members.size());
        members.forEach(sorted::add);
        sorted.sort(Comparator.<JsonNode, String>comparing(m -> uniqueNameOf(m).toLowerCase(Locale.ROOT))
                .thenComparing(QueryCacheKey::uniqueNameOf));
        ArrayNode out = MAPPER.createArrayNode();
        sorted.forEach(out::add);
        return out;
    }

    private static String uniqueNameOf(JsonNode member) {
        JsonNode un = member.get("uniqueName");
        return un == null || un.isNull() ? "" : un.asText();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** DTO used for deterministic serialization — only fields that affect MDX. */
    private static final class CanonicalView {
        public String type;
        public String mdx;
        public String queryType;
        public SaikuCube cube;
        public ThinQueryModel queryModel;
        public String cubeVersion;

        // Omitted when empty so a no-role key is byte-identical to the legacy two-arg key
        // (existing on-disk caches stay valid); present + sorted once roles are in play.
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> roles;

        @JsonIgnore
        public Object getName() {
            return null; // kept for IDE/reflection tools; Jackson ignores via @JsonIgnore
        }
    }
}
