/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import java.util.ArrayList;
import java.util.List;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;

/**
 * Jackson-friendly projection of {@link DraftSchema} for the UI tree.
 *
 * <p>Mirrors the draft-schema shape but uses plain records so Jackson (and the Svelte client) can
 * serialise/consume without needing access to the mutable service-layer classes. Produced by
 * {@link #from(DraftSchema)}.
 */
public record DraftView(String schemaName, List<CubeView> cubes, List<SharedDimView> sharedDimensions) {

    public static DraftView from(DraftSchema draft) {
        if (draft == null) {
            return null;
        }
        List<CubeView> cubes = new ArrayList<>(draft.cubes().size());
        for (DraftCube c : draft.cubes()) {
            cubes.add(CubeView.from(c));
        }
        List<SharedDimView> shared = new ArrayList<>(draft.sharedDimensions().size());
        for (DraftDimension d : draft.sharedDimensions()) {
            shared.add(SharedDimView.from(d));
        }
        return new DraftView(draft.name(), cubes, shared);
    }

    public record CubeView(String name, String factTable, List<DimView> dimensions, List<MeasureView> measures) {
        public static CubeView from(DraftCube c) {
            List<DimView> dims = new ArrayList<>();
            for (DraftDimension d : c.dimensions()) {
                dims.add(DimView.from(d));
            }
            List<MeasureView> measures = new ArrayList<>();
            for (DraftMeasure m : c.measures()) {
                measures.add(MeasureView.from(m));
            }
            return new CubeView(c.name(), c.sourceFactTable(), dims, measures);
        }
    }

    public record DimView(
            String name, String type, String sourceTable, String foreignKey, List<HierarchyView> hierarchies) {
        public static DimView from(DraftDimension d) {
            List<HierarchyView> hs = new ArrayList<>();
            for (DraftHierarchy h : d.hierarchies()) {
                hs.add(HierarchyView.from(h));
            }
            return new DimView(
                    d.name(), d.type() == null ? null : d.type().name(), d.sourceTable(), d.foreignKey(), hs);
        }
    }

    public record HierarchyView(String name, String primaryKey, List<LevelView> levels) {
        public static HierarchyView from(DraftHierarchy h) {
            List<LevelView> ls = new ArrayList<>();
            for (DraftLevel l : h.levels()) {
                ls.add(LevelView.from(l));
            }
            return new HierarchyView(h.name(), h.primaryKey(), ls);
        }
    }

    public record LevelView(String name, String column, String type) {
        public static LevelView from(DraftLevel l) {
            return new LevelView(
                    l.name(), l.column(), l.type() == null ? null : l.type().name());
        }
    }

    public record MeasureView(String name, String column, String aggregator) {
        public static MeasureView from(DraftMeasure m) {
            return new MeasureView(
                    m.name(),
                    m.column(),
                    m.aggregator() == null ? null : m.aggregator().name());
        }
    }

    /** Shared (schema-scoped) dimension — same shape as the in-cube view. */
    public record SharedDimView(String name, String type, String sourceTable, List<HierarchyView> hierarchies) {
        public static SharedDimView from(DraftDimension d) {
            List<HierarchyView> hs = new ArrayList<>();
            for (DraftHierarchy h : d.hierarchies()) {
                hs.add(HierarchyView.from(h));
            }
            return new SharedDimView(
                    d.name(), d.type() == null ? null : d.type().name(), d.sourceTable(), hs);
        }
    }
}
