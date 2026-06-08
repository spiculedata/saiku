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
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinSelection;

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
            // Deliberately do NOT sort measure or hierarchy lists: their order is
            // part of the query (outer-to-inner axis nesting, measure column
            // order). Member selection order is semantically a set — safe to
            // sort, and we do that below inside sortMembersOnly().
            sortMembersOnly(query);
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

            byte[] json = MAPPER.writeValueAsBytes(view);
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
     * Best-effort cube-version fingerprint. Phase 5 Task 5 placeholder —
     * intended to be replaced by a real {@code SaikuCubeMetadataVersionService}
     * once cube-metadata versioning lands.
     *
     * <p>TODO: replace with a real cube metadata version service that tracks
     * schema reloads / DDL changes. For now we mix connection + catalog +
     * cube name so a cube being reconfigured (catalog swap) naturally
     * invalidates the cache.
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
        sb.append(nullSafe(c.getName()));
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Only sort member selections (semantically a set); leave measure and
     *  hierarchy lists in their on-axis order. */
    private static void sortMembersOnly(ThinQuery q) {
        ThinQueryModel model = q.getQueryModel();
        if (model == null) {
            return;
        }
        Map<ThinQueryModel.AxisLocation, ThinAxis> axes = model.getAxes();
        if (axes == null) {
            return;
        }
        for (ThinAxis axis : axes.values()) {
            if (axis.getHierarchies() == null) continue;
            for (ThinHierarchy h : axis.getHierarchies()) {
                if (h.getLevels() == null) continue;
                for (ThinLevel level : h.getLevels().values()) {
                    ThinSelection sel = level.getSelection();
                    if (sel != null && sel.getMembers() != null) {
                        List<ThinMember> members = new ArrayList<>(sel.getMembers());
                        members.sort(Comparator.comparing(
                                mm -> nullSafe(mm.getUniqueName()).toLowerCase(Locale.ROOT)));
                        sel.setMembers(members);
                    }
                }
            }
        }
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
