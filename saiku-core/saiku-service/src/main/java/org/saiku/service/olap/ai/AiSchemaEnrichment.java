/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM/schema-generator overlay applied on top of an {@link AiSchema} by
 * {@link AiSchemaEnricher}. The overlay only changes display fields and
 * adds suggestions — canonical names used for validation are always
 * preserved on the underlying schema, so an agent can ignore the
 * enrichment and still get the same validation contract.
 *
 * <p>{@code renames} keys are slash-paths into the schema:
 * <ul>
 *   <li>{@code measures.Store Sales}</li>
 *   <li>{@code dimensions.Time}</li>
 *   <li>{@code dimensions.Time.hierarchies.Time By}</li>
 *   <li>{@code dimensions.Time.hierarchies.Time By.levels.Quarter}</li>
 * </ul>
 */
public class AiSchemaEnrichment {

    private Map<String, String> renames = new LinkedHashMap<>();
    private List<AiSchemaSuggestion> suggestions = new ArrayList<>();

    public Map<String, String> getRenames() { return renames; }
    public void setRenames(Map<String, String> v) { this.renames = v == null ? new LinkedHashMap<>() : v; }

    public List<AiSchemaSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<AiSchemaSuggestion> v) { this.suggestions = v == null ? new ArrayList<>() : v; }
}
