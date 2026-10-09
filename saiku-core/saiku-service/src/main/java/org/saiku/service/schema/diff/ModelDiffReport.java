/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.util.List;

/**
 * The finished answer to "what does this model change break?" (saiku#1434).
 *
 * <p>Immutable and serialisable: the REST layer returns it verbatim, the CLI renders it as
 * Markdown, and the CI hook posts {@link #markdown()} as a PR comment.
 */
public record ModelDiffReport(
        ModelFormat format,
        String beforeName,
        String afterName,
        List<ModelChange> changes,
        List<BrokenReference> brokenReferences,
        int filesScanned) {

    public ModelDiffReport {
        changes = List.copyOf(changes);
        brokenReferences = List.copyOf(brokenReferences);
    }

    public boolean hasBreakingChanges() {
        return changes.stream().anyMatch(ModelChange::breaking);
    }

    public long breakingChangeCount() {
        return changes.stream().filter(ModelChange::breaking).count();
    }

    /** The distinct repository files holding at least one broken reference. */
    public int affectedFileCount() {
        return (int)
                brokenReferences.stream().map(BrokenReference::file).distinct().count();
    }

    /**
     * Whether the change is safe to merge: nothing in the repository is left pointing at a
     * member that no longer exists.
     *
     * <p>Deliberately <em>not</em> "no breaking changes" — a rename is breaking by definition,
     * yet a rename whose references the same commit updated is exactly the change this feature
     * exists to make mergeable. The CI gate asks this question; {@link #hasBreakingChanges()} is
     * there for a reviewer who wants to know what moved.
     */
    public boolean isClean() {
        return brokenReferences.isEmpty();
    }

    /**
     * Render the report as the Markdown block saiku#1434 asks for: per-cube change lists, then the
     * broken-reference section. This is the exact text the CI hook comments on a pull request.
     */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        if (changes.isEmpty() && brokenReferences.isEmpty()) {
            return "## Semantic model diff\n\nNo model changes and no broken references.\n";
        }
        for (String cube : ModelDiffEngine.cubesTouched(changes)) {
            List<ModelChange> cubeChanges = changes.stream()
                    .filter(c -> c.cube().equalsIgnoreCase(cube))
                    .toList();
            if (cubeChanges.isEmpty()) {
                continue;
            }
            // "FoodMart / Sales" — the schema name qualifies the cube whenever it is not the same
            // string, which is what a reviewer needs to tell two Sales cubes apart.
            sb.append("## ").append(qualified(cube, beforeName)).append('\n');
            for (ModelChange change : cubeChanges) {
                sb.append("- ").append(describe(change)).append('\n');
            }
            sb.append('\n');
        }
        int affected = affectedFileCount();
        sb.append("## Broken references (")
                .append(affected)
                .append(affected == 1 ? " file affected" : " files affected")
                .append(")\n");
        if (brokenReferences.isEmpty()) {
            sb.append("\nNone — every saved query and dashboard still resolves.\n");
        } else {
            sb.append('\n');
            for (BrokenReference reference : brokenReferences) {
                sb.append("- ").append(reference.describe()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String qualified(String cube, String schemaName) {
        if (schemaName == null || schemaName.isBlank() || schemaName.equalsIgnoreCase(cube)) {
            return cube;
        }
        return schemaName + " / " + cube;
    }

    private static String describe(ModelChange change) {
        return switch (change.type()) {
            case ADDED -> "Added " + change.kind().label() + ": " + change.path();
            case REMOVED -> "Removed " + change.kind().label() + ": " + change.path();
            case RENAMED -> "Renamed " + change.kind().label() + ": " + change.fromName() + " \u2192 "
                    + change.toName();
            case MODIFIED -> "Modified " + change.kind().label() + ": " + change.path();
        };
    }
}
