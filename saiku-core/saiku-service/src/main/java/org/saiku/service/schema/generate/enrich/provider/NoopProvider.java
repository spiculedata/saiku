/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.HierarchyOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.path.SchemaPathResolver;

/**
 * Offline rule-based {@link LlmProvider}.
 *
 * <p>Emits three classes of suggestion:
 *
 * <ul>
 *   <li><b>Rename</b>: converts snake_case / camelCase / lowercase-word names into Title Case With
 *       Spaces captions — e.g. {@code order_date} → {@code "Order Date"}.
 *   <li><b>Hierarchy</b>: when a dimension's levels include geography-keyword columns
 *       ({@code country}, {@code region}, {@code state}, {@code province}, {@code city},
 *       {@code postcode}, {@code zip}, etc.) proposes a "Geography" hierarchy over those columns.
 *   <li><b>Aggregator</b>: measure-name prefixes {@code avg_*} / suffixes {@code *_rate} → {@code
 *       AVG}; {@code distinct_*} → {@code DISTINCT_COUNT}; {@code count_*} / {@code *_count} →
 *       {@code COUNT}.
 * </ul>
 *
 * Ops are emitted in stable order: renames, then hierarchies, then aggregators. Pure function:
 * stateless, deterministic, makes no network calls.
 */
public final class NoopProvider implements LlmProvider {

    /** Columns whose names suggest a geographical level, for the geo-hierarchy heuristic. */
    private static final Set<String> GEO_KEYWORDS = Set.of(
            "country",
            "country_code",
            "countrycode",
            "region",
            "state",
            "province",
            "city",
            "postcode",
            "postalcode",
            "postal_code",
            "zip",
            "zipcode");

    private static final double CONFIDENCE = 0.6;

    @Override
    public EnrichResponse enrich(EnrichRequest request) {
        DraftSchema draft = request.draft();

        List<RenameOp> renames = new ArrayList<>();
        List<HierarchyOp> hierarchies = new ArrayList<>();
        List<AggregatorOp> aggregators = new ArrayList<>();

        for (DraftCube cube : draft.cubes()) {
            String cubePath = SchemaPathResolver.pathFor(cube);
            maybeRename(cubePath, cube.name(), renames);

            for (DraftDimension dim : cube.dimensions()) {
                String dimPath = SchemaPathResolver.pathFor(dim, cube);
                visitDimension(dim, dimPath, renames, hierarchies);
            }

            for (DraftMeasure m : cube.measures()) {
                String mPath = SchemaPathResolver.pathFor(m, cube);
                maybeRename(mPath, m.name(), renames);
                maybeAggregator(mPath, m, aggregators);
            }
        }

        for (DraftDimension dim : draft.sharedDimensions()) {
            String dimPath = SchemaPathResolver.pathForShared(dim);
            visitDimension(dim, dimPath, renames, hierarchies);
        }

        SuggestionSet set = new SuggestionSet();
        for (RenameOp op : renames) {
            set.add(op);
        }
        for (HierarchyOp op : hierarchies) {
            set.add(op);
        }
        for (AggregatorOp op : aggregators) {
            set.add(op);
        }
        return new EnrichResponse(set);
    }

    private void visitDimension(
            DraftDimension dim, String dimPath, List<RenameOp> renames, List<HierarchyOp> hierarchies) {
        maybeRename(dimPath, dim.name(), renames);

        List<String> geoColumns = new ArrayList<>();
        for (DraftHierarchy h : dim.hierarchies()) {
            String hPath = dimPath + "/hierarchies/" + SchemaPathResolver.hierarchySegment(h);
            maybeRename(hPath, h.name(), renames);
            for (DraftLevel lvl : h.levels()) {
                String lPath = hPath + "/levels/" + SchemaPathResolver.levelSegment(lvl);
                maybeRename(lPath, lvl.name(), renames);
                if (isGeoColumn(lvl.column())) {
                    if (!geoColumns.contains(lvl.column())) {
                        geoColumns.add(lvl.column());
                    }
                }
            }
        }

        if (!geoColumns.isEmpty()) {
            hierarchies.add(new HierarchyOp(
                    dimPath,
                    "Geography",
                    Collections.unmodifiableList(geoColumns),
                    CONFIDENCE,
                    "Detected geographical columns by keyword match"));
        }
    }

    private void maybeRename(String path, String name, List<RenameOp> out) {
        if (name == null || name.isBlank()) {
            return;
        }
        String caption = toTitleCase(name);
        if (caption.equals(name)) {
            return;
        }
        out.add(new RenameOp(path, name, caption, null, CONFIDENCE, "Title-cased from raw identifier"));
    }

    private void maybeAggregator(String path, DraftMeasure m, List<AggregatorOp> out) {
        String name = m.name();
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase();
        DraftMeasure.Aggregator proposed = null;
        String reason = null;
        if (lower.startsWith("avg_") || lower.endsWith("_rate") || lower.equals("rate")) {
            proposed = DraftMeasure.Aggregator.AVG;
            reason = "Name suggests an average / rate measure";
        } else if (lower.startsWith("distinct_")) {
            proposed = DraftMeasure.Aggregator.DISTINCT_COUNT;
            reason = "Name suggests a distinct-count measure";
        } else if (lower.startsWith("count_") || lower.endsWith("_count")) {
            proposed = DraftMeasure.Aggregator.COUNT;
            reason = "Name suggests a count measure";
        }
        if (proposed == null || proposed == m.aggregator()) {
            return;
        }
        out.add(new AggregatorOp(path, m.aggregator(), proposed, CONFIDENCE, reason));
    }

    private static boolean isGeoColumn(String column) {
        if (column == null) {
            return false;
        }
        String normalized = column.toLowerCase();
        return GEO_KEYWORDS.contains(normalized);
    }

    /**
     * Convert a raw identifier into a Title Case With Spaces caption. Returns the input unchanged
     * if it is already in that form (no transform needed).
     */
    static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // Already Title Case With Spaces? Leave alone.
        if (isAlreadyTitleCased(input)) {
            return input;
        }
        List<String> tokens = tokenize(input);
        if (tokens.isEmpty()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(capitalize(tokens.get(i)));
        }
        return sb.toString();
    }

    private static boolean isAlreadyTitleCased(String s) {
        if (s.indexOf('_') >= 0) {
            return false;
        }
        // If it contains a space, each space-separated token must start with uppercase and have
        // no internal uppercase (no CamelCase inside).
        if (s.indexOf(' ') >= 0) {
            String[] parts = s.split(" ");
            for (String p : parts) {
                if (p.isEmpty()) {
                    return false;
                }
                if (!Character.isUpperCase(p.charAt(0))) {
                    return false;
                }
                for (int i = 1; i < p.length(); i++) {
                    if (Character.isUpperCase(p.charAt(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
        // Single token, no spaces, no underscores — only "already title cased" when it's a single
        // capitalised word (e.g. "Sales"). A single lowercase word ("sales") or a CamelCase
        // identifier ("OrderDate") needs transforming.
        if (!Character.isUpperCase(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Split an identifier on underscores and case boundaries. */
    private static List<String> tokenize(String input) {
        // First split on underscores.
        String[] parts = input.split("_");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            // Then split on case boundaries within each chunk.
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(part.charAt(i - 1))) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(c);
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
        }
        // Dedupe empties and return.
        List<String> cleaned = new ArrayList<>(out.size());
        for (String s : out) {
            if (!s.isEmpty()) {
                cleaned.add(s);
            }
        }
        return cleaned;
    }

    private static String capitalize(String token) {
        if (token.isEmpty()) {
            return token;
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1).toLowerCase();
    }
}
