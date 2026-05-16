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
                // Already MDX-qualified — pass through. We deliberately don't
                // validate further; if it's wrong, Mondrian will say so and
                // the caller's existing 400-translation logic surfaces it.
                out.append(raw);
            } else {
                out.append(resolveBare(raw, schema));
            }
        }
        return out.toString();
    }

    private static String resolveBare(String token, AiSchema schema) {
        String key = AiSchema.key(token);

        // 1. Measure direct hit.
        AiSchema.Measure m = schema.measures.get(key);
        if (m != null) return m.uniqueName;

        // 2. Measure alias (Phase-3 enrichment displayName).
        String mAlias = schema.measureAliases.get(key);
        if (mAlias != null) {
            AiSchema.Measure aliased = schema.measures.get(mAlias);
            if (aliased != null) return aliased.uniqueName;
        }

        // 3. Level name across every hierarchy. We walk the dimension map in
        //    insertion order so the resolver's behaviour is deterministic
        //    when (rarely) two hierarchies share a level name.
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                AiSchema.Level level = hier.levels.get(key);
                if (level != null) return level.uniqueName;
                String lAlias = hier.levelAliases.get(key);
                if (lAlias != null) {
                    AiSchema.Level aliased = hier.levels.get(lAlias);
                    if (aliased != null) return aliased.uniqueName;
                }
            }
        }

        throw new AiValidationException(
                "returns",
                "Unknown returns column '" + token + "'. Use either a bare measure/level caption "
                        + "or a fully-qualified MDX identifier like [Time].[Time].[Year].",
                candidates(schema));
    }

    /** Build the candidate list surfaced in the validation error so an agent
     *  can self-correct from the response without a separate schema fetch. */
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
}
