package org.saiku.service.schema.generate.draft;

/** Draft level. {@link Type} mirrors Mondrian level types we currently emit. */
public class DraftLevel {

    public enum Type {
        REGULAR,
        YEARS,
        QUARTERS,
        MONTHS,
        DAYS
    }

    private String name;
    private String column;
    private Type type;
    private Provenance provenance;

    public DraftLevel(String name, String column, Type type, Provenance provenance) {
        this.name = name;
        this.column = column;
        this.type = type;
        this.provenance = provenance;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String column() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public Type type() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Provenance provenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }
}
