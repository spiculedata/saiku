/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.List;
import java.util.Objects;

/**
 * A named, admin-authored workflow the LLM can invoke when the user's ask matches its description.
 *
 * <p>Skills are markdown files with YAML frontmatter, laid out under {@code saiku-home/skills/}.
 * The parser (see {@link AgentSkillParser}) reads the frontmatter into the record's typed fields
 * and keeps the body verbatim so it can be pasted into the LLM system prompt without further
 * interpretation.
 *
 * <p>Two invocation paths:
 *
 * <ul>
 *   <li>Slash: the DimSum widget offers a slash menu ({@code /weekly-revenue-report}) — the user
 *       picks a skill by name; the ask endpoint prefixes the ask with the skill's steps.
 *   <li>Natural language: the LLM sees the skill catalogue as part of its system prompt and picks a
 *       matching skill on its own when the user's ask lines up with a description.
 * </ul>
 *
 * <p>Fields are immutable; the record is safe to hand to concurrent readers.
 */
public record AgentSkill(String name, String description, String cube, String body, String sourcePath) {

    public AgentSkill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name must be non-blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("skill description must be non-blank");
        }
    }

    /**
     * A compact projection safe to serialise to REST clients — omits the full body so the catalogue
     * response stays small when a site has dozens of skills.
     */
    public record Summary(String name, String description, String cube) {}

    public Summary asSummary() {
        return new Summary(name, description, cube);
    }

    /**
     * A projection safe to inline into the LLM system prompt. Each skill contributes one bullet:
     * name, one-line description, and — when set — the target cube ref so the model can gate the
     * skill by scope.
     */
    public String promptFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("- /").append(name).append(": ").append(oneLine(description));
        if (cube != null && !cube.isBlank()) {
            sb.append(" [cube: ").append(cube).append("]");
        }
        return sb.toString();
    }

    /**
     * Collapse whitespace and cap length so the catalogue projection stays a single line even if
     * the frontmatter description was authored as a multi-line block scalar.
     */
    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 297) + "..." : flat;
    }

    /**
     * Build a system-prompt block that lists every skill in {@code skills}. Empty when no skills are
     * registered — the caller should skip appending the whole section rather than emit a "no skills"
     * marker.
     */
    public static String catalogPromptFragment(List<AgentSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Available skills (admin-authored workflows). When the user's question closely ")
                .append("matches one of these — or when the message starts with `/<skill-name>` — use ")
                .append("the skill's steps to structure your response instead of freewheeling:\n");
        for (AgentSkill s : skills) {
            sb.append(s.promptFragment()).append('\n');
        }
        return sb.toString();
    }
}
