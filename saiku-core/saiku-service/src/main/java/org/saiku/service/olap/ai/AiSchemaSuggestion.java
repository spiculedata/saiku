/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * A single LLM/schema-generator suggestion exposed via {@code /ai/schema}.
 * Agents may consider these when constructing queries but aren't required
 * to follow them.
 */
public class AiSchemaSuggestion {

    private String op;          // e.g. "rename", "promote", "addAggregator"
    private String targetPath;  // e.g. "dimensions.Time.hierarchies.Time By"
    private double confidence;  // 0.0 - 1.0
    private String rationale;
    private String suggestedValue;

    public AiSchemaSuggestion() {}

    public AiSchemaSuggestion(String op, String targetPath, double confidence,
                              String rationale, String suggestedValue) {
        this.op = op;
        this.targetPath = targetPath;
        this.confidence = confidence;
        this.rationale = rationale;
        this.suggestedValue = suggestedValue;
    }

    public String getOp() { return op; }
    public void setOp(String v) { this.op = v; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String v) { this.targetPath = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }
    public String getRationale() { return rationale; }
    public void setRationale(String v) { this.rationale = v; }
    public String getSuggestedValue() { return suggestedValue; }
    public void setSuggestedValue(String v) { this.suggestedValue = v; }
}
