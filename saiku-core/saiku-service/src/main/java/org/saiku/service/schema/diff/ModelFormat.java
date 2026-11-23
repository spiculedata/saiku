/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.util.Locale;

/**
 * The model serialisation formats the diff engine understands (saiku#1434).
 *
 * <p>Detection is content-first, filename-second. A payload that opens with {@code <} is
 * Mondrian XML whatever it is called; a payload carrying {@code ontology_mappings} /
 * {@code semantic_model} is Ossie YAML. Only when the content is inconclusive (an empty string,
 * a truncated file) do we fall back to the file extension, and a still-ambiguous payload is
 * rejected with {@link ModelDiffException.Reason#UNKNOWN_FORMAT} rather than guessed at —
 * diffing a YAML document against a Mondrian schema is meaningless output, and the fuzz tests
 * (saiku#1434) require that malformed input fails loudly instead of diffing to "no changes".
 */
public enum ModelFormat {
    MONDRIAN_XML("Mondrian XML"),
    OSSIE_YAML("Ossie YAML");

    private final String displayName;

    ModelFormat(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Resolve the format of a model payload.
     *
     * @param content    the raw model text (may be null / empty)
     * @param sourceName the file name the content came from, used only as a tie-breaker. May
     *                   be null.
     * @throws ModelDiffException when neither the content nor the name identifies a known format
     */
    public static ModelFormat detect(String content, String sourceName) {
        String trimmed = content == null ? "" : content.stripLeading();
        if (trimmed.startsWith("<")) {
            return MONDRIAN_XML;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("ontology_mappings") || lower.contains("semantic_model")) {
            return OSSIE_YAML;
        }
        if (sourceName != null) {
            String name = sourceName.toLowerCase(Locale.ROOT);
            if (name.endsWith(".xml")) {
                return MONDRIAN_XML;
            }
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                return OSSIE_YAML;
            }
        }
        throw new ModelDiffException(
                ModelDiffException.Reason.UNKNOWN_FORMAT,
                "cannot tell whether '"
                        + (sourceName == null ? "<inline payload>" : sourceName)
                        + "' is a Mondrian XML schema or an Ossie YAML model");
    }
}
