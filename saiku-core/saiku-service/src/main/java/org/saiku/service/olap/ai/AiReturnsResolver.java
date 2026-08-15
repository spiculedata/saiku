/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a {@code returns=} drillthrough query-string into the fully-
 * qualified MDX form Mondrian's {@code DRILLTHROUGH ... RETURN ...} clause
 * expects. Closes saiku#782 — the AI contract promise is "agents never see
 * MDX", but the row JSON returned from a drillthrough keys cells by bare
 * caption, leaving no way for an agent to construct a follow-up
 * {@code returns=} from a previous result.
 *
 * <p>Rules per comma-separated entry:
 *
 * <ul>
 *   <li>Already bracketed ({@code [...]}) → passed through verbatim. Agents
 *       that already speak MDX aren't surprised.</li>
 *   <li>Bare token → matched (case-insensitively) against:
 *     <ol>
 *       <li>{@link AiSchema#measures} by canonical name and
 *           {@link AiSchema#measureAliases}.</li>
 *       <li>Every {@link AiSchema.Level} across every hierarchy by canonical
 *           name and level aliases.</li>
 *     </ol>
 *     The first match wins and its {@code uniqueName} is substituted in.</li>
 *   <li>No match → {@link AiValidationException} naming the offending token
 *       and listing the candidate set (measure names + level names) so an
 *       agent can self-correct without scraping the schema endpoint.</li>
 * </ul>
 *
 * <p>Stateless and pure — no olap4j, no Mondrian dependency — so the
 * resolver is testable in isolation from the live cube.
 */
public final class AiReturnsResolver {

    private AiReturnsResolver() {}

    /**
     * Resolve a comma-separated {@code returns=} value against the given
     * schema. {@code null}/blank inputs return {@code null} so the caller
     * can pass-through to the downstream drillthrough unchanged.
     *
     * @throws AiValidationException if any non-bracketed token can't be
     *         matched to a measure or level in the schema.
     */
    public static String resolve(String returns, AiSchema schema) {
        if (returns == null || returns.trim().isEmpty()) return returns;
        if (schema == null) return returns; // legacy/test path — leave alone.
        String[] parts = returns.split(",");
        StringBuilder out = new StringBuilder(returns.length() + 32);
        boolean first = true;
        for (String part : parts) {
            String raw = part.trim();
            if (raw.isEmpty()) continue; // tolerate stray commas
            if (!first) out.append(',');
            first = false;
            if (raw.startsWith("[")) {
                // Already MDX-qualified. We deliberately don't validate the IDENTIFIER further —
                // if it's wrong, Mondrian will say so and the caller's existing 400-translation
                // logic surfaces it. But the PII gate is policy, not validation, so it must apply
                // to this spelling too (saiku#1848): the bracketed branch used to skip checkPii
                // entirely, so a column refused as `Salary` sailed through as
                // `[Measures].[Salary]`. The refusal message even recommends the qualified form,
                // so an agent following the self-correction hint walked straight into the bypass.
                out.append(checkPiiByUniqueName(raw, schema));
            } else {
                out.append(resolveBare(raw, schema));
            }
        }
        return out.toString();
    }

    /**
     * saiku#1848 PII gate for the already-qualified spelling. Matches {@code uniqueName} against
     * the schema's measures and levels and refuses if the match is PII-flagged.
     *
     * <p>An identifier that matches NOTHING is returned unchanged, preserving the long-standing
     * "let Mondrian judge unknown MDX" behaviour — this method adds a refusal, never a new
     * rejection. Comparison is case-insensitive because MDX identifiers are.
     */
    private static String checkPiiByUniqueName(String uniqueName, AiSchema schema) {
        for (AiSchema.Measure m : schema.measures.values()) {
            if (m.uniqueName != null && m.uniqueName.equalsIgnoreCase(uniqueName)) {
                return checkPii(m, uniqueName, schema);
            }
        }
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                for (AiSchema.Level level : hier.levels.values()) {
                    if (level.uniqueName != null && level.uniqueName.equalsIgnoreCase(uniqueName)) {
                        return checkPii(level, uniqueName, schema);
                    }
                }
            }
        }
        return uniqueName;
    }

    private static String resolveBare(String token, AiSchema schema) {
        String key = AiSchema.key(token);

        // 1. Measure direct hit.
        AiSchema.Measure m = schema.measures.get(key);
        if (m != null) return checkPii(m, token, schema);

        // 2. Measure alias (Phase-3 enrichment displayName).
        String mAlias = schema.measureAliases.get(key);
        if (mAlias != null) {
            AiSchema.Measure aliased = schema.measures.get(mAlias);
            if (aliased != null) return checkPii(aliased, token, schema);
        }

        // 3. Level name across every hierarchy. We walk the dimension map in
        //    insertion order so the resolver's behaviour is deterministic
        //    when (rarely) two hierarchies share a level name.
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                AiSchema.Level level = hier.levels.get(key);
                if (level != null) return checkPii(level, token, schema);
                String lAlias = hier.levelAliases.get(key);
                if (lAlias != null) {
                    AiSchema.Level aliased = hier.levels.get(lAlias);
                    if (aliased != null) return checkPii(aliased, token, schema);
                }
            }
        }

        throw new AiValidationException(
                "returns",
                "Unknown returns column '" + token + "'. Use either a bare measure/level caption "
                        + "or a fully-qualified MDX identifier like [Time].[Time].[Year].",
                candidates(schema));
    }

    /**
     * saiku#902 PII gate. The resolver has matched {@code token} to a
     * concrete measure / level; if the schema author flagged it
     * {@code saiku.semantic.pii=true}, refuse rather than emit the
     * uniqueName for downstream MDX.
     *
     * <p>The error envelope carries the same shape as a regular unknown-
     * column error so the agent's self-correction path is uniform (look at
     * {@code field=returns}, pick from {@code available}). The
     * {@code available} list explicitly excludes PII columns so the agent
     * never sees the names of redacted slots even via the error response.
     */
    private static String checkPii(AiSchema.Measure m, String token, AiSchema schema) {
        if (m.pii) {
            throw new AiPiiException(
                    "returns",
                    "Column '" + token + "' is annotated PII (saiku.semantic.pii=true) and cannot be "
                            + "projected through DRILLTHROUGH ... RETURN. Pick a non-PII column from the "
                            + "candidate list.",
                    nonPiiCandidates(schema));
        }
        return m.uniqueName;
    }

    private static String checkPii(AiSchema.Level l, String token, AiSchema schema) {
        if (l.pii) {
            throw new AiPiiException(
                    "returns",
                    "Column '" + token + "' is annotated PII (saiku.semantic.pii=true) and cannot be "
                            + "projected through DRILLTHROUGH ... RETURN. Pick a non-PII column from the "
                            + "candidate list.",
                    nonPiiCandidates(schema));
        }
        return l.uniqueName;
    }

    /** Build the candidate list surfaced in the standard validation error so
     *  an agent can self-correct from the response without a separate schema
     *  fetch. Includes ALL measures + levels — the standard unknown-column
     *  path is about typos, not policy. */
    private static List<String> candidates(AiSchema schema) {
        Set<String> all = new LinkedHashSet<>();
        for (AiSchema.Measure m : schema.measures.values()) all.add(m.name);
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                for (AiSchema.Level level : hier.levels.values()) all.add(level.name);
            }
        }
        return new ArrayList<>(all);
    }

    /** Same as {@link #candidates(AiSchema)} but filters out PII-flagged
     *  measures + levels. Used on the PII refusal path so the suggestion list
     *  doesn't leak the names of redacted slots — even via a self-correct
     *  fallback the agent could mine for column existence. */
    private static List<String> nonPiiCandidates(AiSchema schema) {
        Set<String> all = new LinkedHashSet<>();
        for (AiSchema.Measure m : schema.measures.values()) {
            if (!m.pii) all.add(m.name);
        }
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                for (AiSchema.Level level : hier.levels.values()) {
                    if (!level.pii) all.add(level.name);
                }
            }
        }
        return new ArrayList<>(all);
    }
}
