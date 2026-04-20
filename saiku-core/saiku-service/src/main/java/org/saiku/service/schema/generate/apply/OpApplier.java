package org.saiku.service.schema.generate.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.DegenerateDimOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.IgnoreOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Applies a {@link SuggestionOp} to a {@link DraftSchema} in place.
 *
 * <p>Path resolution follows the grammar documented on
 * {@code org.saiku.service.schema.generate.enrich.provider.LlmProvider}:
 *
 * <pre>
 *   cubes/Sales
 *   cubes/Sales/dimensions/customer
 *   cubes/Sales/dimensions/customer/hierarchies/customer
 *   cubes/Sales/dimensions/customer/hierarchies/customer/levels/name
 *   cubes/Sales/measures/Amount
 *   sharedDimensions/Time
 * </pre>
 *
 * <p>Every mutation stamps provenance to {@link Provenance.Source#USER}. Path lookup failures
 * raise {@link IllegalArgumentException} with the attempted path and the available siblings.
 *
 * <p>v1 semantics:
 *
 * <ul>
 *   <li>{@link HierarchyOp} REPLACES all hierarchies on the targeted dimension with a single new
 *       hierarchy — multi-hierarchy merging is a future refinement.
 *   <li>{@link DegenerateDimOp} does not validate that {@code factColumn} exists on the fact
 *       table; that is the job of a separate validator.
 * </ul>
 */
public class OpApplier {

    private static final Provenance USER_APPLY = new Provenance(Provenance.Source.USER, "user:apply", 1.0);

    public void apply(DraftSchema schema, SuggestionOp op) {
        if (op instanceof RenameOp r) {
            applyRename(schema, r);
        } else if (op instanceof AggregatorOp a) {
            applyAggregator(schema, a);
        } else if (op instanceof IgnoreOp i) {
            applyIgnore(schema, i);
        } else if (op instanceof HierarchyOp h) {
            applyHierarchy(schema, h);
        } else if (op instanceof DegenerateDimOp d) {
            applyDegenerateDim(schema, d);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported op type: " + op.getClass().getName());
        }
    }

    // -- per-op handlers ---------------------------------------------------

    private void applyRename(DraftSchema schema, RenameOp op) {
        Object node = resolve(schema, op.targetPath());
        String newName = op.newCaption();
        if (node instanceof DraftCube c) {
            if (!c.name().equals(newName)) {
                c.setName(newName);
            }
            c.setProvenance(USER_APPLY);
        } else if (node instanceof DraftDimension d) {
            if (!d.name().equals(newName)) {
                d.setName(newName);
            }
            d.setProvenance(USER_APPLY);
        } else if (node instanceof DraftHierarchy h) {
            if (!h.name().equals(newName)) {
                h.setName(newName);
            }
            h.setProvenance(USER_APPLY);
        } else if (node instanceof DraftLevel l) {
            if (!l.name().equals(newName)) {
                l.setName(newName);
            }
            l.setProvenance(USER_APPLY);
        } else if (node instanceof DraftMeasure m) {
            if (!m.name().equals(newName)) {
                m.setName(newName);
            }
            m.setProvenance(USER_APPLY);
        } else {
            throw new IllegalArgumentException("RenameOp target is not a renameable element: " + op.targetPath());
        }
    }

    private void applyAggregator(DraftSchema schema, AggregatorOp op) {
        Object node = resolve(schema, op.targetPath());
        if (!(node instanceof DraftMeasure m)) {
            throw new IllegalArgumentException("AggregatorOp target must be a measure, got "
                    + (node == null ? "null" : node.getClass().getSimpleName())
                    + " at "
                    + op.targetPath());
        }
        m.setAggregator(op.newAggregator());
        m.setProvenance(USER_APPLY);
    }

    private void applyIgnore(DraftSchema schema, IgnoreOp op) {
        Resolved r = resolveWithParent(schema, op.targetPath());
        r.removeFromParent();
    }

    private void applyHierarchy(DraftSchema schema, HierarchyOp op) {
        Object node = resolve(schema, op.targetPath());
        if (!(node instanceof DraftDimension d)) {
            throw new IllegalArgumentException("HierarchyOp target must be a dimension, got "
                    + (node == null ? "null" : node.getClass().getSimpleName())
                    + " at "
                    + op.targetPath());
        }
        DraftHierarchy rebuilt = new DraftHierarchy(op.hierarchyName(), null, USER_APPLY);
        for (String col : op.levelColumns()) {
            rebuilt.levels().add(new DraftLevel(col, col, DraftLevel.Type.REGULAR, USER_APPLY));
        }
        d.hierarchies().clear();
        d.hierarchies().add(rebuilt);
        d.setProvenance(USER_APPLY);
    }

    private void applyDegenerateDim(DraftSchema schema, DegenerateDimOp op) {
        Object node = resolve(schema, op.targetPath());
        if (!(node instanceof DraftCube cube)) {
            throw new IllegalArgumentException("DegenerateDimOp target must be a cube, got "
                    + (node == null ? "null" : node.getClass().getSimpleName())
                    + " at "
                    + op.targetPath());
        }
        DraftDimension dim = new DraftDimension(op.dimName(), DraftDimension.Type.STANDARD, USER_APPLY);
        dim.setSourceTable(cube.sourceFactTable());
        DraftHierarchy hier = new DraftHierarchy(op.dimName(), null, USER_APPLY);
        hier.levels().add(new DraftLevel(op.dimName(), op.factColumn(), DraftLevel.Type.REGULAR, USER_APPLY));
        dim.hierarchies().add(hier);
        cube.dimensions().add(dim);
    }

    // -- path resolution ---------------------------------------------------

    /** Result of path resolution: the node, plus a callback to remove it from its parent. */
    private static final class Resolved {
        final Object node;
        final Runnable remover;

        Resolved(Object node, Runnable remover) {
            this.node = node;
            this.remover = remover;
        }

        void removeFromParent() {
            remover.run();
        }
    }

    private Object resolve(DraftSchema schema, String path) {
        return resolveWithParent(schema, path).node;
    }

    private Resolved resolveWithParent(DraftSchema schema, String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("targetPath must not be empty");
        }
        String[] segs = path.split("/");
        int i = 0;
        String root = segs[i++];
        if ("cubes".equals(root)) {
            return resolveCube(schema, segs, i, path);
        } else if ("sharedDimensions".equals(root)) {
            return resolveSharedDim(schema, segs, i, path);
        }
        throw new IllegalArgumentException(
                "Unknown path root '" + root + "' in '" + path + "' — expected 'cubes' or 'sharedDimensions'");
    }

    private Resolved resolveCube(DraftSchema schema, String[] segs, int i, String fullPath) {
        if (i >= segs.length) {
            throw new IllegalArgumentException("Missing cube name in path: " + fullPath);
        }
        String cubeName = segs[i++];
        DraftCube cube = findByName(schema.cubes(), DraftCube::name, cubeName);
        if (cube == null) {
            throw notFound(fullPath, "cube", cubeName, names(schema.cubes(), DraftCube::name));
        }
        if (i >= segs.length) {
            return new Resolved(cube, () -> schema.cubes().remove(cube));
        }
        String kind = segs[i++];
        if ("measures".equals(kind)) {
            if (i >= segs.length) {
                throw new IllegalArgumentException("Missing measure name in path: " + fullPath);
            }
            String mName = segs[i++];
            DraftMeasure m = findByName(cube.measures(), DraftMeasure::name, mName);
            if (m == null) {
                throw notFound(fullPath, "measure", mName, names(cube.measures(), DraftMeasure::name));
            }
            if (i != segs.length) {
                throw new IllegalArgumentException("Trailing segments after measure in path: " + fullPath);
            }
            return new Resolved(m, () -> cube.measures().remove(m));
        } else if ("dimensions".equals(kind)) {
            return resolveDim(cube.dimensions(), segs, i, fullPath);
        }
        throw new IllegalArgumentException(
                "Unknown cube child '" + kind + "' in '" + fullPath + "' — expected 'measures' or 'dimensions'");
    }

    private Resolved resolveSharedDim(DraftSchema schema, String[] segs, int i, String fullPath) {
        if (i >= segs.length) {
            throw new IllegalArgumentException("Missing shared dimension name in path: " + fullPath);
        }
        return resolveDim(schema.sharedDimensions(), segs, i, fullPath);
    }

    private Resolved resolveDim(List<DraftDimension> parent, String[] segs, int i, String fullPath) {
        String dimName = segs[i++];
        DraftDimension dim = findByName(parent, DraftDimension::name, dimName);
        if (dim == null) {
            throw notFound(fullPath, "dimension", dimName, names(parent, DraftDimension::name));
        }
        if (i >= segs.length) {
            return new Resolved(dim, () -> parent.remove(dim));
        }
        String kind = segs[i++];
        if (!"hierarchies".equals(kind)) {
            throw new IllegalArgumentException(
                    "Unknown dimension child '" + kind + "' in '" + fullPath + "' — expected 'hierarchies'");
        }
        if (i >= segs.length) {
            throw new IllegalArgumentException("Missing hierarchy name in path: " + fullPath);
        }
        String hName = segs[i++];
        DraftHierarchy hier = findByName(dim.hierarchies(), DraftHierarchy::name, hName);
        if (hier == null) {
            throw notFound(fullPath, "hierarchy", hName, names(dim.hierarchies(), DraftHierarchy::name));
        }
        if (i >= segs.length) {
            return new Resolved(hier, () -> dim.hierarchies().remove(hier));
        }
        String kind2 = segs[i++];
        if (!"levels".equals(kind2)) {
            throw new IllegalArgumentException(
                    "Unknown hierarchy child '" + kind2 + "' in '" + fullPath + "' — expected 'levels'");
        }
        if (i >= segs.length) {
            throw new IllegalArgumentException("Missing level name in path: " + fullPath);
        }
        String lName = segs[i++];
        DraftLevel lvl = findByName(hier.levels(), DraftLevel::name, lName);
        if (lvl == null) {
            throw notFound(fullPath, "level", lName, names(hier.levels(), DraftLevel::name));
        }
        if (i != segs.length) {
            throw new IllegalArgumentException("Trailing segments after level in path: " + fullPath);
        }
        return new Resolved(lvl, () -> hier.levels().remove(lvl));
    }

    private <T> T findByName(List<T> xs, java.util.function.Function<T, String> nameOf, String name) {
        for (T x : xs) {
            if (name.equals(nameOf.apply(x))) {
                return x;
            }
        }
        return null;
    }

    private <T> List<String> names(List<T> xs, java.util.function.Function<T, String> nameOf) {
        List<String> out = new ArrayList<>(xs.size());
        for (T x : xs) {
            out.add(nameOf.apply(x));
        }
        return out;
    }

    private IllegalArgumentException notFound(String fullPath, String kind, String missing, List<String> available) {
        return new IllegalArgumentException("No element at path "
                + fullPath
                + " — "
                + kind
                + " '"
                + missing
                + "' not found; available: "
                + available.stream().collect(Collectors.joining(", ", "[", "]")));
    }
}
