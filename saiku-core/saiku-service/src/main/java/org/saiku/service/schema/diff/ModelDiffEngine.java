/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diffs two {@link ModelSnapshot}s into an ordered list of {@link ModelChange}s (saiku#1434).
 *
 * <p>The three-step shape is: <em>match by identity</em> (kind + owner + name), then pair up
 * unmatched removes and adds within the same owner bucket as renames, then report whatever is
 * left as plain adds and removes.
 *
 * <h2>Why rename detection is signature-based</h2>
 *
 * Renaming a measure or a level is the single most common reviewable model edit, and from the
 * reference walker's point of view it is indistinguishable from a remove plus an add. Without
 * pairing, every intentional rename produces a wall of "broken reference" noise — the opposite
 * of the signal this feature exists to provide.
 *
 * <p>Pairing therefore requires the two elements to be <em>the same element</em> under a
 * different name, judged on their attribute signature (everything except the name — column,
 * aggregator, format, expression). A candidate pair must score at least
 * {@value #RENAME_SIMILARITY_THRESHOLD} on a Jaccard similarity over signature tokens, and an
 * exact signature match short-circuits to 1.0. This is deliberately conservative: a rename we
 * miss degrades to "removed + added", which is still correct output, whereas a rename we invent
 * would hide a real removal. The threshold is fixed rather than tunable so the fuzz tests have a
 * stable contract.
 */
public final class ModelDiffEngine {

    /** Minimum Jaccard similarity over signature tokens before two elements count as renamed. */
    public static final double RENAME_SIMILARITY_THRESHOLD = 0.6d;

    /**
     * Diff two snapshots of the same model.
     *
     * @throws ModelDiffException.Reason#CROSS_FORMAT when the snapshots are in different formats
     */
    public List<ModelChange> diff(ModelSnapshot before, ModelSnapshot after) {
        if (before.format() != after.format()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.CROSS_FORMAT,
                    "refusing to diff " + before.format().displayName() + " 'before' against "
                            + after.format().displayName() + " 'after' — the two sides must be the same model format");
        }
        List<ModelChange> changes = new ArrayList<>();

        // 1. Elements present on both sides under the same identity.
        Map<String, ModelElement> beforeByKey = new LinkedHashMap<>();
        for (ModelElement element : before.elements()) {
            beforeByKey.putIfAbsent(element.key(), element);
        }
        Set<String> matched = new HashSet<>();
        for (ModelElement now : after.elements()) {
            ModelElement then = beforeByKey.get(now.key());
            if (then == null) {
                continue;
            }
            matched.add(now.key());
            matched.add(then.key());
            if (!then.signature().equals(now.signature())) {
                changes.add(ModelChange.modified(then, now));
            }
        }

        // 2. Unmatched before/after elements, bucketed by owner so a rename is only ever paired
        //    inside the cube (and container) it happened in.
        List<ModelElement> removed = unmatched(before, matched);
        List<ModelElement> added = unmatched(after, matched);
        List<ModelChange> renames = new ArrayList<>();

        // 2a. A renamed cube takes its members with it, so pairing members requires knowing the
        //     old and new cube are the same cube first. Matched on the member set: renaming a
        //     cube while also adding a measure still matches, because the fingerprints are
        //     compared with the same similarity threshold as any other rename.
        Map<String, String> cubeAliases = pairRenamedCubes(removed, added, matched, renames);

        List<ModelChange> memberRenames = pairRenames(removed, added, matched, cubeAliases);
        renames.addAll(memberRenames);
        changes.addAll(renames);

        // 3. Whatever is still unmatched is a plain add or remove.
        for (ModelElement element : removed) {
            if (!matched.contains(element.key())) {
                changes.add(ModelChange.removed(element));
            }
        }
        for (ModelElement element : added) {
            if (!matched.contains(element.key())) {
                changes.add(ModelChange.added(element));
            }
        }
        changes.sort(comparator());
        return List.copyOf(changes);
    }

    private static List<ModelElement> unmatched(ModelSnapshot snapshot, Set<String> matched) {
        List<ModelElement> out = new ArrayList<>();
        for (ModelElement element : snapshot.elements()) {
            if (!matched.contains(element.key())) {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * Greedily pair removals with additions that share a kind and an owner. Greedy on the
     * highest-scoring pair first, with names used as a deterministic tie-break so the same input
     * always yields the same report.
     */
    private List<ModelChange> pairRenames(
            List<ModelElement> removed,
            List<ModelElement> added,
            Set<String> matched,
            Map<String, String> cubeAliases) {
        List<ModelChange> renames = new ArrayList<>();
        Set<String> consumedAdded = new HashSet<>();
        // Candidate pairs, best score first.
        record Candidate(ModelElement from, ModelElement to, double score) {}
        List<Candidate> candidates = new ArrayList<>();
        for (ModelElement from : removed) {
            if (from.kind() == ModelElementKind.CUBE) {
                continue; // handled by pairRenamedCubes
            }
            for (ModelElement to : added) {
                if (to.kind() == ModelElementKind.CUBE) {
                    continue;
                }
                if (from.kind() != to.kind() || !sameOwner(from, to, cubeAliases)) {
                    continue;
                }
                double score = similarity(from.signature(), to.signature());
                if (score >= RENAME_SIMILARITY_THRESHOLD) {
                    candidates.add(new Candidate(from, to, score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score)
                .reversed()
                .thenComparing(c -> c.from().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(c -> c.to().name(), String.CASE_INSENSITIVE_ORDER));

        Set<String> consumedRemoved = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (consumedRemoved.contains(candidate.from().key())
                    || consumedAdded.contains(candidate.to().key())) {
                continue;
            }
            consumedRemoved.add(candidate.from().key());
            consumedAdded.add(candidate.to().key());
            matched.add(candidate.from().key());
            matched.add(candidate.to().key());
            renames.add(ModelChange.renamed(candidate.from(), candidate.to()));
        }
        return renames;
    }

    /**
     * Pair removed cubes with added cubes that carry the same members, and return the
     * {@code oldCube -> newCube} mapping the member pass needs to keep pairing inside the right
     * owner.
     */
    private Map<String, String> pairRenamedCubes(
            List<ModelElement> removed, List<ModelElement> added, Set<String> matched, List<ModelChange> renames) {
        record CubeCandidate(ModelElement from, ModelElement to, double score) {}
        List<CubeCandidate> candidates = new ArrayList<>();
        for (ModelElement from : removed) {
            if (from.kind() != ModelElementKind.CUBE) {
                continue;
            }
            for (ModelElement to : added) {
                if (to.kind() != ModelElementKind.CUBE) {
                    continue;
                }
                double score = similarity(memberFingerprint(removed, from.name()), memberFingerprint(added, to.name()));
                if (score >= RENAME_SIMILARITY_THRESHOLD) {
                    candidates.add(new CubeCandidate(from, to, score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(CubeCandidate::score)
                .reversed()
                .thenComparing(c -> c.from().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(c -> c.to().name(), String.CASE_INSENSITIVE_ORDER));

        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> usedCubes = new HashSet<>();
        for (CubeCandidate candidate : candidates) {
            if (usedCubes.contains(candidate.from().key())
                    || usedCubes.contains(candidate.to().key())) {
                continue;
            }
            usedCubes.add(candidate.from().key());
            usedCubes.add(candidate.to().key());
            matched.add(candidate.from().key());
            matched.add(candidate.to().key());
            aliases.put(
                    candidate.from().name().toLowerCase(Locale.ROOT),
                    candidate.to().name());
            renames.add(ModelChange.renamed(candidate.from(), candidate.to()));
        }
        return aliases;
    }

    /** The set of {@code kind|container|name} member identities belonging to a cube. */
    private static Set<String> memberFingerprint(List<ModelElement> elements, String cube) {
        Set<String> out = new LinkedHashSet<>();
        for (ModelElement element : elements) {
            if (element.kind() == ModelElementKind.CUBE) {
                continue;
            }
            if (element.cube().equalsIgnoreCase(cube)) {
                out.add(element.kind().label()
                        + '|'
                        + normalise(element.container())
                        + '|'
                        + normalise(element.name()));
            }
        }
        return out;
    }

    private static boolean sameOwner(ModelElement a, ModelElement b, Map<String, String> cubeAliases) {
        return ownerCube(a, cubeAliases).equalsIgnoreCase(ownerCube(b, cubeAliases))
                && (a.container() == null
                        ? b.container() == null
                        : a.container().equalsIgnoreCase(b.container()));
    }

    /** The cube an element belongs to, mapped through a cube-rename alias when one applies. */
    private static String ownerCube(ModelElement element, Map<String, String> cubeAliases) {
        if (element.kind() == ModelElementKind.CUBE) {
            return element.name();
        }
        return cubeAliases.getOrDefault(element.cube().toLowerCase(Locale.ROOT), element.cube());
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Jaccard similarity over the {@code key=value} tokens of two signatures. An element whose
     * signature is a single token (say {@code column=unit_sales}) compared against another single
     * token scores 1.0 only when they are equal, so a same-shaped-but-different-column edit is
     * never mistaken for a rename.
     */
    static double similarity(String a, String b) {
        return similarity(tokens(a), tokens(b));
    }

    static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            // Two attribute-less elements of the same kind in the same owner: a rename is the
            // only plausible reading, and the alternative is to report a remove plus an add.
            return 1.0d;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        long intersection = left.stream().filter(right::contains).count();
        return (double) intersection / union.size();
    }

    private static Set<String> tokens(String signature) {
        Set<String> out = new LinkedHashSet<>();
        if (signature == null || signature.isBlank()) {
            return out;
        }
        for (String token : signature.split(";")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Report order: cubes first, then their measures, then dimensions and their levels; within a
     * group, removals and renames before additions, because those are the reviewable ones.
     */
    private static Comparator<ModelChange> comparator() {
        return Comparator.comparingInt((ModelChange c) -> c.kind().ordinal())
                .thenComparing(c -> c.cube(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(c -> c.container() == null ? "" : c.container(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(c -> switch (c.type()) {
                    case REMOVED -> 0;
                    case RENAMED -> 1;
                    case MODIFIED -> 2;
                    case ADDED -> 3;
                })
                .thenComparing(c -> c.path(), String.CASE_INSENSITIVE_ORDER);
    }

    /** The distinct cube names touched by a set of changes, sorted — used for the report header. */
    public static Set<String> cubesTouched(List<ModelChange> changes) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ModelChange change : changes) {
            out.add(change.cube());
        }
        return out;
    }
}
