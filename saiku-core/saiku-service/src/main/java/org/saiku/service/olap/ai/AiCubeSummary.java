/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Compact cube description for the {@code GET /ai/cubes} listing.
 * Agents use this to decide which cube to fetch the full schema for.
 */
public class AiCubeSummary {

    private String connectionName;
    private String catalog;
    private String schema;
    private String cubeName;
    private String cubeCaption;
    private String defaultMeasure;
    private int measureCount;

    public AiCubeSummary() {}

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String v) {
        this.connectionName = v;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String v) {
        this.catalog = v;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String v) {
        this.schema = v;
    }

    public String getCubeName() {
        return cubeName;
    }

    public void setCubeName(String v) {
        this.cubeName = v;
    }

    public String getCubeCaption() {
        return cubeCaption;
    }

    public void setCubeCaption(String v) {
        this.cubeCaption = v;
    }

    public String getDefaultMeasure() {
        return defaultMeasure;
    }

    public void setDefaultMeasure(String v) {
        this.defaultMeasure = v;
    }

    public int getMeasureCount() {
        return measureCount;
    }

    public void setMeasureCount(int v) {
        this.measureCount = v;
    }
}
