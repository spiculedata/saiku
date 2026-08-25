/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.draft;

import java.util.ArrayList;
import java.util.List;

/** Draft hierarchy. Optional one-hop snowflake join captured in {@link DraftJoin}. */
public class DraftHierarchy {

    private String name;
    private String primaryKey;
    private DraftJoin join;
    private final List<DraftLevel> levels = new ArrayList<>();
    private Provenance provenance;

    public DraftHierarchy(String name, String primaryKey, Provenance provenance) {
        this.name = name;
        this.primaryKey = primaryKey;
        this.provenance = provenance;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String primaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public DraftJoin join() {
        return join;
    }

    public void setJoin(DraftJoin join) {
        this.join = join;
    }

    public List<DraftLevel> levels() {
        return levels;
    }

    public Provenance provenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }
}
