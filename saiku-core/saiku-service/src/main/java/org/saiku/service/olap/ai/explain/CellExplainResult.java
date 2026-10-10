/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything "explain this number" knows about one cell, and the wire shape of
 * {@code POST /saiku/api/ai/explain}.
 *
 * <p>Fields the server couldn't produce are {@code null} with a line in {@link #getNotes()} rather
 * than a placeholder string — a panel that shows "SQL unavailable on this backend" is honest, one
 * that shows {@code SELECT ?} is not. {@code narrative} degrades the same way, and
 * {@link #getNarrativeSource()} always says which of the three it is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CellExplainResult {

    /** Cube the cell belongs to (its unique name). */
    private String cube;
    /** Caption of the measure on the column axis, when there is one. */
    private String measure;
    /** Human path of the row coordinate, e.g. {@code USA / CA / Altadena}. */
    private String rowPath;
    /** Human path of the column coordinate, e.g. {@code 1997 Q1}. */
    private String columnPath;
    /** Unformatted value of the cell. */
    private double value;
    /** Value as the cellset rendered it. */
    private String formatted;
    /** MDX of the parent cellset — what the user asked for, as issued. */
    private String mdx;
    /** MDX of the single cell — what actually computes this number. */
    private String cellMdx;
    /** SQL Mondrian emitted for {@link #cellMdx}, when it could be captured. */
    private String sql;
    /** Structured findings (Phase 2). Never null; may be empty. */
    private List<ExplainDriver> drivers = new ArrayList<>();
    /** The story (Phase 3), or the deterministic Phase-2 summary when the LLM is unavailable. */
    private String narrative;

    private NarrativeSource narrativeSource;
    /** Model that produced the narrative; {@code null} unless {@code narrativeSource == LLM}. */
    private String model;
    /** Anything the server dropped or degraded on, in plain English. Never null. */
    private List<String> notes = new ArrayList<>();
    /** Server-side wall time of the whole explain, in ms. */
    private long elapsedMs;

    public enum NarrativeSource {
        /** Written by the configured LLM provider, grounded on {@link #getDrivers()}. */
        LLM,
        /** Deterministic summary built from the drivers — also the fallback when the LLM fails. */
        COMPUTED,
        /** No narrative at all (e.g. the caller asked without one and no LLM is configured). */
        NONE
    }

    public String getCube() {
        return cube;
    }

    public void setCube(String cube) {
        this.cube = cube;
    }

    public String getMeasure() {
        return measure;
    }

    public void setMeasure(String measure) {
        this.measure = measure;
    }

    public String getRowPath() {
        return rowPath;
    }

    public void setRowPath(String rowPath) {
        this.rowPath = rowPath;
    }

    public String getColumnPath() {
        return columnPath;
    }

    public void setColumnPath(String columnPath) {
        this.columnPath = columnPath;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public String getFormatted() {
        return formatted;
    }

    public void setFormatted(String formatted) {
        this.formatted = formatted;
    }

    public String getMdx() {
        return mdx;
    }

    public void setMdx(String mdx) {
        this.mdx = mdx;
    }

    public String getCellMdx() {
        return cellMdx;
    }

    public void setCellMdx(String cellMdx) {
        this.cellMdx = cellMdx;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<ExplainDriver> getDrivers() {
        return drivers;
    }

    public void setDrivers(List<ExplainDriver> drivers) {
        this.drivers = drivers == null ? new ArrayList<>() : new ArrayList<>(drivers);
    }

    public String getNarrative() {
        return narrative;
    }

    public void setNarrative(String narrative) {
        this.narrative = narrative;
    }

    public NarrativeSource getNarrativeSource() {
        return narrativeSource;
    }

    public void setNarrativeSource(NarrativeSource narrativeSource) {
        this.narrativeSource = narrativeSource;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes == null ? new ArrayList<>() : new ArrayList<>(notes);
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
