package org.saiku.service.schema.generate.apply;

import java.util.Optional;
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
import org.saiku.service.schema.generate.path.SchemaPathResolver;

/**
 * Applies a {@link SuggestionOp} to a {@link DraftSchema} in place.
 *
 * <p>Path resolution follows the stable-id grammar on
 * {@link SchemaPathResolver} — segments are physical identifiers (column / table names), never
 * user-visible captions. This way a {@link RenameOp} that mutates a measure's {@code name} does
 * not invalidate the path of any subsequent op targeting the same element.
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
        Object node = resolveRequired(schema, op.targetPath());
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
        Object node = resolveRequired(schema, op.targetPath());
        if (!(node instanceof DraftMeasure m)) {
            throw new IllegalArgumentException("AggregatorOp target must be a measure, got "
                    + node.getClass().getSimpleName()
                    + " at "
                    + op.targetPath());
        }
        m.setAggregator(op.newAggregator());
        m.setProvenance(USER_APPLY);
    }

    private void applyIgnore(DraftSchema schema, IgnoreOp op) {
        SchemaPathResolver.ParentRef ref = SchemaPathResolver.resolveWithParent(schema, op.targetPath())
                .orElseThrow(() -> new IllegalArgumentException("No element at path " + op.targetPath()));
        ref.removeFromParent();
    }

    private void applyHierarchy(DraftSchema schema, HierarchyOp op) {
        Object node = resolveRequired(schema, op.targetPath());
        if (!(node instanceof DraftDimension d)) {
            throw new IllegalArgumentException("HierarchyOp target must be a dimension, got "
                    + node.getClass().getSimpleName()
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
        Object node = resolveRequired(schema, op.targetPath());
        if (!(node instanceof DraftCube cube)) {
            throw new IllegalArgumentException("DegenerateDimOp target must be a cube, got "
                    + node.getClass().getSimpleName()
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

    private Object resolveRequired(DraftSchema schema, String path) {
        Optional<Object> node = SchemaPathResolver.resolve(schema, path);
        return node.orElseThrow(() -> new IllegalArgumentException("No element at path " + path));
    }
}
