package org.saiku.service.schema.generate.draft;

import java.util.ArrayList;
import java.util.List;

/** Draft dimension. {@code sourceTable} may be null for role-played usages of a shared dim. */
public class DraftDimension {

    public enum Type {
        STANDARD,
        TIME
    }

    private String name;
    private Type type;
    private String sourceTable;
    private String foreignKey;
    private final List<DraftHierarchy> hierarchies = new ArrayList<>();
    private Provenance provenance;

    public DraftDimension(String name, Type type, Provenance provenance) {
        this.name = name;
        this.type = type;
        this.provenance = provenance;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type type() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String sourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String foreignKey() {
        return foreignKey;
    }

    public void setForeignKey(String foreignKey) {
        this.foreignKey = foreignKey;
    }

    public List<DraftHierarchy> hierarchies() {
        return hierarchies;
    }

    public Provenance provenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }
}
