/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;

/**
 * Shared path utility for addressing draft-schema elements by <em>stable identifier</em> rather
 * than by user-visible caption. Consumed by the delta reconciler, the op applier, and the
 * suggestion providers — all three must use the same grammar so that a rename op accepted by the
 * user does not invalidate the path of any subsequent op targeting the same element.
 *
 * <p>Stable-id rules:
 *
 * <ul>
 *   <li>Cube segment → {@code sourceFactTable} (physical fact-table name).
 *   <li>Dimension segment → {@code sourceTable} (physical dim-table name; may be null for
 *       role-played shared dims — caller uses fallback only when absent).
 *   <li>Hierarchy segment → {@code primaryKey} column.
 *   <li>Level segment → {@code column}.
 *   <li>Measure segment → {@code column}, or the literal string {@code count_star} when the
 *       aggregator is {@link DraftMeasure.Aggregator#COUNT_STAR} (column is null for those).
 * </ul>
 *
 * <p>Grammar:
 *
 * <pre>
 *   cubes/{factTable}
 *   cubes/{factTable}/dimensions/{dimTable}
 *   cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkCol}
 *   cubes/{factTable}/dimensions/{dimTable}/hierarchies/{pkCol}/levels/{levelCol}
 *   cubes/{factTable}/measures/{measureCol|count_star}
 *   sharedDimensions/{dimTable}
 *   sharedDimensions/{dimTable}/hierarchies/{pkCol}
 *   sharedDimensions/{dimTable}/hierarchies/{pkCol}/levels/{levelCol}
 * </pre>
 *
 * <p>If a stable id is blank, {@link #cubeSegment}/{@code dimSegment}/etc. fall back to the
 * element's current {@code name()} so that partially-constructed drafts still address something.
 * This matches the behaviour the reconciler relied on pre-refactor.
 */
public final class SchemaPathResolver {

    private SchemaPathResolver() {}

    // ---------- path construction ----------

    public static String pathFor(DraftCube cube) {
        return "cubes/" + cubeSegment(cube);
    }

    public static String pathFor(DraftDimension dim, DraftCube parentCube) {
        return pathFor(parentCube) + "/dimensions/" + dimSegment(dim);
    }

    public static String pathForShared(DraftDimension dim) {
        return "sharedDimensions/" + dimSegment(dim);
    }

    public static String pathFor(DraftHierarchy h, DraftDimension dim, DraftCube parentCube) {
        return pathFor(dim, parentCube) + "/hierarchies/" + hierarchySegment(h);
    }

    public static String pathForShared(DraftHierarchy h, DraftDimension dim) {
        return pathForShared(dim) + "/hierarchies/" + hierarchySegment(h);
    }

    public static String pathFor(DraftLevel l, DraftHierarchy h, DraftDimension dim, DraftCube parentCube) {
        return pathFor(h, dim, parentCube) + "/levels/" + levelSegment(l);
    }

    public static String pathForShared(DraftLevel l, DraftHierarchy h, DraftDimension dim) {
        return pathForShared(h, dim) + "/levels/" + levelSegment(l);
    }

    public static String pathFor(DraftMeasure m, DraftCube parentCube) {
        return pathFor(parentCube) + "/measures/" + measureSegment(m);
    }

    // ---------- segment helpers ----------

    public static String cubeSegment(DraftCube cube) {
        return safe(cube.sourceFactTable(), cube.name());
    }

    public static String dimSegment(DraftDimension dim) {
        return safe(dim.sourceTable(), dim.name());
    }

    public static String hierarchySegment(DraftHierarchy h) {
        return safe(h.primaryKey(), h.name());
    }

    public static String levelSegment(DraftLevel l) {
        return safe(l.column(), l.name());
    }

    public static String measureSegment(DraftMeasure m) {
        if (m.aggregator() == DraftMeasure.Aggregator.COUNT_STAR) {
            return "count_star";
        }
        return safe(m.column(), m.name());
    }

    private static String safe(String primary, String fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    // ---------- path resolution ----------

    /**
     * Result of a parent-aware resolve: the live node plus a callback that removes it from its
     * parent collection.
     */
    public static final class ParentRef {
        private final Object node;
        private final Runnable remover;
        private final List<String> siblingIds;

        ParentRef(Object node, Runnable remover, List<String> siblingIds) {
            this.node = node;
            this.remover = remover;
            this.siblingIds = siblingIds;
        }

        public Object node() {
            return node;
        }

        public void removeFromParent() {
            remover.run();
        }

        public List<String> siblingIds() {
            return siblingIds;
        }
    }

    /** Resolve a path to its live node (cube/dim/hierarchy/level/measure). */
    public static Optional<Object> resolve(DraftSchema schema, String path) {
        return resolveWithParent(schema, path).map(r -> r.node);
    }

    /**
     * Resolve a path, returning the node and a remover for its parent collection. Useful for
     * IgnoreOp, which must remove the node from its parent.
     */
    public static Optional<ParentRef> resolveWithParent(DraftSchema schema, String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        String[] segs = path.split("/");
        int i = 0;
        if (segs.length == 0) {
            return Optional.empty();
        }
        String root = segs[i++];
        try {
            if ("cubes".equals(root)) {
                return Optional.ofNullable(resolveCube(schema, segs, i, path));
            } else if ("sharedDimensions".equals(root)) {
                return Optional.ofNullable(resolveSharedDim(schema, segs, i, path));
            }
            throw new PathException(
                    "Unknown path root '" + root + "' in '" + path + "' — expected 'cubes' or 'sharedDimensions'");
        } catch (PathException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static ParentRef resolveCube(DraftSchema schema, String[] segs, int i, String fullPath) {
        if (i >= segs.length) {
            throw new PathException("Missing cube segment in path: " + fullPath);
        }
        String cubeSeg = segs[i++];
        DraftCube cube = findByKey(schema.cubes(), SchemaPathResolver::cubeSegment, cubeSeg);
        if (cube == null) {
            throw notFound(fullPath, "cube", cubeSeg, keys(schema.cubes(), SchemaPathResolver::cubeSegment));
        }
        if (i >= segs.length) {
            return new ParentRef(
                    cube, () -> schema.cubes().remove(cube), keys(schema.cubes(), SchemaPathResolver::cubeSegment));
        }
        String kind = segs[i++];
        if ("measures".equals(kind)) {
            if (i >= segs.length) {
                throw new PathException("Missing measure segment in path: " + fullPath);
            }
            String mSeg = segs[i++];
            DraftMeasure m = findByKey(cube.measures(), SchemaPathResolver::measureSegment, mSeg);
            if (m == null) {
                throw notFound(fullPath, "measure", mSeg, keys(cube.measures(), SchemaPathResolver::measureSegment));
            }
            if (i != segs.length) {
                throw new PathException("Trailing segments after measure in path: " + fullPath);
            }
            return new ParentRef(
                    m, () -> cube.measures().remove(m), keys(cube.measures(), SchemaPathResolver::measureSegment));
        } else if ("dimensions".equals(kind)) {
            return resolveDim(cube.dimensions(), segs, i, fullPath);
        }
        throw new PathException(
                "Unknown cube child '" + kind + "' in '" + fullPath + "' — expected 'measures' or 'dimensions'");
    }

    private static ParentRef resolveSharedDim(DraftSchema schema, String[] segs, int i, String fullPath) {
        if (i >= segs.length) {
            throw new PathException("Missing shared dimension segment in path: " + fullPath);
        }
        return resolveDim(schema.sharedDimensions(), segs, i, fullPath);
    }

    private static ParentRef resolveDim(List<DraftDimension> parent, String[] segs, int i, String fullPath) {
        String dimSeg = segs[i++];
        DraftDimension dim = findByKey(parent, SchemaPathResolver::dimSegment, dimSeg);
        if (dim == null) {
            throw notFound(fullPath, "dimension", dimSeg, keys(parent, SchemaPathResolver::dimSegment));
        }
        if (i >= segs.length) {
            return new ParentRef(dim, () -> parent.remove(dim), keys(parent, SchemaPathResolver::dimSegment));
        }
        String kind = segs[i++];
        if (!"hierarchies".equals(kind)) {
            throw new PathException(
                    "Unknown dimension child '" + kind + "' in '" + fullPath + "' — expected 'hierarchies'");
        }
        if (i >= segs.length) {
            throw new PathException("Missing hierarchy segment in path: " + fullPath);
        }
        String hSeg = segs[i++];
        DraftHierarchy hier = findByKey(dim.hierarchies(), SchemaPathResolver::hierarchySegment, hSeg);
        if (hier == null) {
            throw notFound(fullPath, "hierarchy", hSeg, keys(dim.hierarchies(), SchemaPathResolver::hierarchySegment));
        }
        if (i >= segs.length) {
            return new ParentRef(
                    hier,
                    () -> dim.hierarchies().remove(hier),
                    keys(dim.hierarchies(), SchemaPathResolver::hierarchySegment));
        }
        String kind2 = segs[i++];
        if (!"levels".equals(kind2)) {
            throw new PathException(
                    "Unknown hierarchy child '" + kind2 + "' in '" + fullPath + "' — expected 'levels'");
        }
        if (i >= segs.length) {
            throw new PathException("Missing level segment in path: " + fullPath);
        }
        String lSeg = segs[i++];
        DraftLevel lvl = findByKey(hier.levels(), SchemaPathResolver::levelSegment, lSeg);
        if (lvl == null) {
            throw notFound(fullPath, "level", lSeg, keys(hier.levels(), SchemaPathResolver::levelSegment));
        }
        if (i != segs.length) {
            throw new PathException("Trailing segments after level in path: " + fullPath);
        }
        return new ParentRef(
                lvl, () -> hier.levels().remove(lvl), keys(hier.levels(), SchemaPathResolver::levelSegment));
    }

    private static <T> T findByKey(List<T> xs, Function<T, String> keyOf, String key) {
        for (T x : xs) {
            if (key.equals(keyOf.apply(x))) {
                return x;
            }
        }
        return null;
    }

    private static <T> List<String> keys(List<T> xs, Function<T, String> keyOf) {
        List<String> out = new ArrayList<>(xs.size());
        for (T x : xs) {
            out.add(keyOf.apply(x));
        }
        return out;
    }

    private static PathException notFound(String fullPath, String kind, String missing, List<String> available) {
        StringBuilder sb = new StringBuilder();
        sb.append("No element at path ")
                .append(fullPath)
                .append(" — ")
                .append(kind)
                .append(" '")
                .append(missing)
                .append("' not found; available: [");
        for (int j = 0; j < available.size(); j++) {
            if (j > 0) {
                sb.append(", ");
            }
            sb.append(available.get(j));
        }
        sb.append("]");
        return new PathException(sb.toString());
    }

    /** Internal — converted to {@link IllegalArgumentException} at the public boundary. */
    private static final class PathException extends RuntimeException {
        PathException(String msg) {
            super(msg);
        }
    }
}
