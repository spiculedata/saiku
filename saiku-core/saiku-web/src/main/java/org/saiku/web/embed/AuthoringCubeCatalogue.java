/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.olap.dto.ISaikuObject;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * saiku#1435 — a frozen, JSON-serialisable snapshot of <b>one cube</b> for the
 * Creator Mode surface. It plays two roles:
 *
 * <ol>
 *   <li>It is the <em>allowlist</em> {@link AuthoringQueryValidator} checks every
 *       inbound query against, so a creator can only ever name hierarchies,
 *       levels, members and measures of the cube their token pins.</li>
 *   <li>It is the <em>catalogue</em> {@code GET /saiku/api/embed/authoring/context}
 *       returns, so the stripped workbench never has to reach the full discover
 *       API (which is authenticated-only) to populate its pickers.</li>
 * </ol>
 *
 * <p>Member lists are capped ({@link #MAX_MEMBERS_PER_LEVEL}). A level that
 * exceeds the cap is marked {@link Level#truncated} and is deliberately
 * <b>unselectable</b>: the validator refuses any member on such a level rather
 * than accepting a name it could not check. Fail-closed beats a half-working
 * 5,000-member dropdown.
 */
public final class AuthoringCubeCatalogue {

    /** Per-level member cap. Above this the level is truncated (unselectable). */
    public static final int MAX_MEMBERS_PER_LEVEL = 1000;

    private final String cubeUniqueName;
    private final String cubeCaption;
    private final List<Dimension> dimensions;
    private final List<MeasureInfo> measures;

    public AuthoringCubeCatalogue(
            String cubeUniqueName, String cubeCaption, List<Dimension> dimensions, List<MeasureInfo> measures) {
        this.cubeUniqueName = cubeUniqueName;
        this.cubeCaption = cubeCaption;
        this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        this.measures = measures == null ? List.of() : List.copyOf(measures);
    }

    /* --------------------------- model --------------------------- */

    public static final class Dimension {
        public final String name;
        public final String caption;
        public final List<Level> levels;

        public Dimension(String name, String caption, List<Level> levels) {
            this.name = name;
            this.caption = caption;
            this.levels = levels == null ? List.of() : List.copyOf(levels);
        }
    }

    public static final class Level {
        public final String name;
        public final String caption;
        /** Member unique names, in cube order. Empty when {@link #truncated}. */
        public final List<String> members;
        /** True when the real member list exceeded {@link #MAX_MEMBERS_PER_LEVEL}. */
        public final boolean truncated;

        public Level(String name, String caption, List<String> members, boolean truncated) {
            this.name = name;
            this.caption = caption;
            this.members = members == null ? List.of() : List.copyOf(members);
            this.truncated = truncated;
        }
    }

    public static final class MeasureInfo {
        public final String name;
        public final String caption;

        public MeasureInfo(String name, String caption) {
            this.name = name;
            this.caption = caption;
        }
    }

    /* --------------------------- lookup --------------------------- */

    public String cubeUniqueName() {
        return cubeUniqueName;
    }

    public String cubeCaption() {
        return cubeCaption;
    }

    public List<Dimension> dimensions() {
        return dimensions;
    }

    public List<MeasureInfo> measures() {
        return measures;
    }

    /**
     * Resolve the axis hierarchy a client named. Saiku's thin axis model keys
     * a hierarchy either by its <em>dimension</em> unique name or — for a
     * single-hierarchy dimension — by a level unique name, so both spellings
     * resolve. Anything else is outside the pinned cube and the validator
     * refuses it.
     */
    public Dimension dimensionForHierarchy(String hierarchyName) {
        if (hierarchyName == null) {
            return null;
        }
        for (Dimension d : dimensions) {
            if (hierarchyName.equals(d.name)) {
                return d;
            }
        }
        for (Dimension d : dimensions) {
            for (Level l : d.levels) {
                if (l.name.equals(hierarchyName)) {
                    return d;
                }
            }
        }
        return null;
    }

    /** The level with this unique name, or null. */
    public Level level(String levelUniqueName) {
        for (Dimension d : dimensions) {
            for (Level l : d.levels) {
                if (l.name.equals(levelUniqueName)) {
                    return l;
                }
            }
        }
        return null;
    }

    public boolean hasMeasure(String measureUniqueName) {
        if (measureUniqueName == null) {
            return false;
        }
        for (MeasureInfo m : measures) {
            if (m.name.equals(measureUniqueName)) {
                return true;
            }
        }
        return false;
    }

    /* --------------------------- building --------------------------- */

    /**
     * Freeze a live cube through the Saiku discovery DTOs — the exact path the
     * authenticated discover API serves, so a creator sees the catalogue the
     * workbench would have offered. Only <em>visible</em> dimensions,
     * hierarchies, levels and measures are captured, and a level with more
     * members than {@link #MAX_MEMBERS_PER_LEVEL} is marked truncated
     * (unselectable) rather than served partially.
     */
    public static AuthoringCubeCatalogue fromSaikuCube(SaikuCube cube, OlapDiscoverService discover) {
        if (cube == null) {
            throw new IllegalArgumentException("cube is required");
        }
        if (discover == null) {
            throw new IllegalArgumentException("discover service is required");
        }
        List<Dimension> dims = new ArrayList<>();
        for (SaikuDimension d : discover.getAllDimensions(cube)) {
            if (d == null || !d.isVisible() || d.getHierarchies() == null) {
                continue;
            }
            for (SaikuHierarchy h : d.getHierarchies()) {
                if (h == null || !h.isVisible() || h.getLevels() == null) {
                    continue;
                }
                List<Level> levels = new ArrayList<>();
                for (SaikuLevel l : h.getLevels()) {
                    if (l == null || !l.isVisible()) {
                        continue;
                    }
                    levels.add(freezeLevel(cube, discover, l));
                }
                dims.add(new Dimension(d.getUniqueName(), captionOf(d), levels));
            }
        }
        List<MeasureInfo> measures = new ArrayList<>();
        for (SaikuMember m : discover.getMeasures(cube)) {
            if (m == null) {
                continue;
            }
            if (Boolean.TRUE.equals(m.isCalculated()) || Boolean.FALSE.equals(m.isVisible())) {
                // Calculated measures carry formula text assembled from member
                // names — nothing a creator-built query needs — and a hidden
                // measure is one the workbench would never have offered.
                continue;
            }
            measures.add(new MeasureInfo(m.getUniqueName(), captionOf(m)));
        }
        return new AuthoringCubeCatalogue(cube.getUniqueName(), captionOf(cube), dims, measures);
    }

    private static Level freezeLevel(SaikuCube cube, OlapDiscoverService discover, SaikuLevel level) {
        String levelCaption = captionOf(level);
        List<SimpleCubeElement> found;
        try {
            // +1 so "exactly at the cap" and "over the cap" are distinguishable
            // without a second round trip.
            found = discover.getLevelMembers(
                    cube, level.getHierarchyUniqueName(), level.getUniqueName(), MAX_MEMBERS_PER_LEVEL + 1);
        } catch (RuntimeException e) {
            // A level we can't enumerate is truncated with no members: the
            // validator then refuses any member on it (fail closed) and the UI
            // omits it from the member picker.
            return new Level(level.getUniqueName(), levelCaption, List.of(), true);
        }
        if (found == null || found.size() > MAX_MEMBERS_PER_LEVEL) {
            return new Level(level.getUniqueName(), levelCaption, List.of(), true);
        }
        List<String> members = new ArrayList<>(found.size());
        for (SimpleCubeElement e : found) {
            if (e != null) {
                members.add(e.getUniqueName());
            }
        }
        return new Level(level.getUniqueName(), levelCaption, members, false);
    }

    /** Caption with a name fallback — every Saiku DTO exposes a caption getter
     *  but returns null for an un-annotated element. */
    private static String captionOf(ISaikuObject o) {
        String c;
        if (o instanceof SaikuDimension d) {
            c = d.getCaption();
        } else if (o instanceof SaikuHierarchy h) {
            c = h.getCaption();
        } else if (o instanceof SaikuLevel l) {
            c = l.getCaption();
        } else if (o instanceof SaikuMember m) {
            c = m.getCaption();
        } else {
            c = null;
        }
        return (c == null || c.isBlank()) ? o.getName() : c;
    }

    /* --------------------------- json --------------------------- */

    /** Stable, deterministic JSON for {@code GET /authoring/context}. */
    public Map<String, Object> toContextJson(String tenantId, String scopePath) {
        List<Map<String, Object>> dims = new ArrayList<>();
        for (Dimension d : dimensions) {
            List<Map<String, Object>> levels = new ArrayList<>();
            for (Level l : d.levels) {
                levels.add(Map.of(
                        "name", l.name,
                        "caption", l.caption == null ? l.name : l.caption,
                        "truncated", l.truncated,
                        "members", l.members));
            }
            dims.add(Map.of("name", d.name, "caption", d.caption == null ? d.name : d.caption, "levels", levels));
        }
        List<Map<String, Object>> ms = new ArrayList<>();
        for (MeasureInfo m : measures) {
            ms.add(Map.of("name", m.name, "caption", m.caption == null ? m.name : m.caption));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("scopePath", scopePath);
        out.put("cube", cubeUniqueName);
        out.put("cubeCaption", cubeCaption == null ? cubeUniqueName : cubeCaption);
        out.put("dimensions", Collections.unmodifiableList(dims));
        out.put("measures", Collections.unmodifiableList(ms));
        return out;
    }
}
