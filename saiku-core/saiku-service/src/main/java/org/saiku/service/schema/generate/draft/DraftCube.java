/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.draft;

import java.util.ArrayList;
import java.util.List;

/** Draft cube. Mutable so op-applier can rewrite name, swap fact table, etc. */
public class DraftCube {

    private String name;
    private String sourceFactTable;
    private final List<DraftDimension> dimensions = new ArrayList<>();
    private final List<DraftMeasure> measures = new ArrayList<>();
    private Provenance provenance;

    public DraftCube(String name, String sourceFactTable, Provenance provenance) {
        this.name = name;
        this.sourceFactTable = sourceFactTable;
        this.provenance = provenance;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String sourceFactTable() {
        return sourceFactTable;
    }

    public void setSourceFactTable(String sourceFactTable) {
        this.sourceFactTable = sourceFactTable;
    }

    public List<DraftDimension> dimensions() {
        return dimensions;
    }

    public List<DraftMeasure> measures() {
        return measures;
    }

    public Provenance provenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }
}
