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
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
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

    /** Hash a {@link ThinQuery} with the given cube-metadata version fingerprint. */
    public static String of(ThinQuery query, String cubeVersion) {
        if (query == null) {
            throw new IllegalArgumentException("query");
        }
        try {
            sortInPlace(query);
            // Canonical JSON excludes client-only fields like `name` (random UUID),
            // `parameters` (resolved into MDX already), `plugins`, and `metadata`.
            CanonicalView view = new CanonicalView();
            view.type = query.getType() == null ? null : query.getType().name();
            view.mdx = query.getMdx();
            view.queryType = query.getQueryType();
            view.cube = query.getCube();
            view.queryModel = query.getQueryModel();
            view.cubeVersion = cubeVersion == null ? "" : cubeVersion;

            byte[] json = MAPPER.writeValueAsBytes(view);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json);
            return toHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build QueryCacheKey", e);
        }
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

    private static void sortInPlace(ThinQuery q) {
        ThinQueryModel model = q.getQueryModel();
        if (model == null) {
            return;
        }
        if (model.getDetails() != null && model.getDetails().getMeasures() != null) {
            List<ThinMeasure> measures = model.getDetails().getMeasures();
            measures.sort(Comparator.comparing(m -> nullSafe(m.getUniqueName()).toLowerCase(Locale.ROOT)));
        }
        Map<ThinQueryModel.AxisLocation, ThinAxis> axes = model.getAxes();
        if (axes != null) {
            for (ThinAxis axis : axes.values()) {
                if (axis.getHierarchies() != null) {
                    axis.getHierarchies().sort(Comparator.comparing(h -> nullSafe(h.getName())
                            .toLowerCase(Locale.ROOT)));
                    for (ThinHierarchy h : axis.getHierarchies()) {
                        if (h.getLevels() != null) {
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

        @JsonIgnore
        public Object getName() {
            return null; // kept for IDE/reflection tools; Jackson ignores via @JsonIgnore
        }
    }
}
