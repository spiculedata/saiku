package org.saiku.service.schema.generate.delta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.path.SchemaPathResolver;

/**
 * Reconciles a freshly inferred {@link DraftSchema} against a baseline draft (typically loaded
 * from a {@link org.saiku.service.schema.generate.writer.GeneratedSidecar}).
 *
 * <p>Each element in either tree is assigned a {@link DeltaTag}, keyed by a <em>stable</em>
 * target path built from physical identifiers (column / table names), <strong>not</strong> from
 * user-visible captions. Path construction is delegated to {@link SchemaPathResolver} so the
 * reconciler, {@code OpApplier}, and suggestion providers all share one grammar.
 */
public class DeltaReconciler {

    public DeltaReport reconcile(DraftSchema baseline, DraftSchema current) {
        Map<String, DeltaTag> baselineMap = new LinkedHashMap<>();
        walk(baseline, baselineMap::put);

        Map<String, DeltaTag> tags = new LinkedHashMap<>();
        walk(current, (path, ignored) -> {
            if (baselineMap.remove(path) != null) {
                tags.put(path, DeltaTag.EXISTING);
            } else {
                tags.put(path, DeltaTag.NEW);
            }
        });

        // whatever remains in baselineMap was dropped upstream
        for (String path : baselineMap.keySet()) {
            tags.put(path, DeltaTag.REMOVED_UPSTREAM);
        }

        List<String> newPaths = new ArrayList<>();
        List<String> existingPaths = new ArrayList<>();
        List<String> removedPaths = new ArrayList<>();
        for (Map.Entry<String, DeltaTag> e : tags.entrySet()) {
            switch (e.getValue()) {
                case NEW -> newPaths.add(e.getKey());
                case EXISTING -> existingPaths.add(e.getKey());
                case REMOVED_UPSTREAM -> removedPaths.add(e.getKey());
            }
        }

        return new DeltaReport(tags, newPaths, existingPaths, removedPaths);
    }

    /** Functional sink so {@link #walk} can feed either the baseline map or the current map. */
    @FunctionalInterface
    private interface PathSink {
        void accept(String path, DeltaTag placeholder);
    }

    private void walk(DraftSchema schema, PathSink sink) {
        for (DraftDimension shared : schema.sharedDimensions()) {
            String path = SchemaPathResolver.pathForShared(shared);
            sink.accept(path, DeltaTag.EXISTING);
            walkDimensionChildren(path, shared, sink);
        }

        for (DraftCube cube : schema.cubes()) {
            String cubePath = SchemaPathResolver.pathFor(cube);
            sink.accept(cubePath, DeltaTag.EXISTING);

            for (DraftDimension dim : cube.dimensions()) {
                String dimPath = SchemaPathResolver.pathFor(dim, cube);
                sink.accept(dimPath, DeltaTag.EXISTING);
                walkDimensionChildren(dimPath, dim, sink);
            }

            for (DraftMeasure m : cube.measures()) {
                String measurePath = cubePath + "/measures/" + SchemaPathResolver.measureSegment(m);
                sink.accept(measurePath, DeltaTag.EXISTING);
            }
        }
    }

    private void walkDimensionChildren(String dimPath, DraftDimension dim, PathSink sink) {
        for (DraftHierarchy h : dim.hierarchies()) {
            String hierPath = dimPath + "/hierarchies/" + SchemaPathResolver.hierarchySegment(h);
            // hierarchies aren't a first-class element in the tag map today (levels are what
            // matters for delta), but we record them so users could filter on the hierarchy
            // itself. Keep commented-out to avoid noise — uncomment if downstream needs them.
            // sink.accept(hierPath, DeltaTag.EXISTING);
            for (DraftLevel l : h.levels()) {
                String levelPath = hierPath + "/levels/" + SchemaPathResolver.levelSegment(l);
                sink.accept(levelPath, DeltaTag.EXISTING);
            }
        }
    }
}
