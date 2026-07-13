/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.List;
import java.util.Objects;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * A named, admin-authored persona that scopes an AI ask surface.
 *
 * <p>Spaces bundle five things that make an ask feel like it belongs to a role rather than
 * "whatever cube happens to be in front of me":
 *
 * <ul>
 *   <li>A {@code systemPrompt} that anchors the LLM in the persona ("You are the Sales Analyst
 *       for FoodMart. Prefer weekly and monthly grain unless asked otherwise…").
 *   <li>A {@code cubeAllowlist} — the server refuses to ask against cubes outside this list, so
 *       the persona genuinely owns its scope and can't be prompted around.
 *   <li>A {@code skillAllowlist} — subset of {@link AgentSkill}s the persona can reach ("the Sales
 *       Analyst gets forecast-primer, not churn-cohort"). Empty list = allow all.
 *   <li>A list of {@code suggestedPrompts} the UI surfaces as quick-start chips.
 *   <li>An identity ({@code id}, {@code name}, {@code description}) for the sidebar +
 *       {@code /ai/spaces} catalogue.
 * </ul>
 *
 * <p>Persisted as JSON under {@code saiku-home/agent-spaces/*.json}. See
 * {@link AgentSpaceParser} for the on-disk shape.
 */
public record AgentSpace(
        String id,
        String name,
        String description,
        String systemPrompt,
        List<AiCubeRef> cubeAllowlist,
        List<String> skillAllowlist,
        List<String> suggestedPrompts,
        String sourcePath) {

    public AgentSpace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (id.isBlank()) {
            throw new IllegalArgumentException("space id must be non-blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("space name must be non-blank");
        }
        cubeAllowlist = cubeAllowlist == null ? List.of() : List.copyOf(cubeAllowlist);
        skillAllowlist = skillAllowlist == null ? List.of() : List.copyOf(skillAllowlist);
        suggestedPrompts = suggestedPrompts == null ? List.of() : List.copyOf(suggestedPrompts);
    }

    /**
     * True when {@code ref} matches one of the allowlisted cubes on all four coordinates
     * (connection, catalog, schema, cube name). An empty allowlist matches nothing — the space
     * would be unusable — but that's the operator's responsibility to catch at authoring time; the
     * enforcement layer stays strict.
     */
    public boolean allowsCube(AiCubeRef ref) {
        if (ref == null) {
            return false;
        }
        for (AiCubeRef allowed : cubeAllowlist) {
            if (matches(allowed, ref)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code skillName} is in the allowlist, OR when the allowlist is empty (no skill
     * filter set — all skills flow through).
     */
    public boolean allowsSkill(String skillName) {
        if (skillName == null) {
            return false;
        }
        return skillAllowlist.isEmpty() || skillAllowlist.contains(skillName);
    }

    /**
     * Default cube for asks that don't name one explicitly. The first entry of the allowlist wins;
     * spaces with an empty allowlist have no default and callers must supply one.
     */
    public AiCubeRef defaultCube() {
        return cubeAllowlist.isEmpty() ? null : cubeAllowlist.get(0);
    }

    /**
     * Compact projection safe to serialise to REST clients — omits the system prompt and cube
     * allowlist so the catalogue endpoint doesn't leak routing decisions to unauthenticated
     * embeds. The UI has everything it needs (name, description, suggested prompts) to render a
     * space picker.
     */
    public record Summary(String id, String name, String description, List<String> suggestedPrompts) {}

    public Summary asSummary() {
        return new Summary(id, name, description, suggestedPrompts);
    }

    private static boolean matches(AiCubeRef a, AiCubeRef b) {
        return Objects.equals(a.getConnectionName(), b.getConnectionName())
                && Objects.equals(a.getCatalog(), b.getCatalog())
                && Objects.equals(a.getSchema(), b.getSchema())
                && Objects.equals(a.getCubeName(), b.getCubeName());
    }
}
