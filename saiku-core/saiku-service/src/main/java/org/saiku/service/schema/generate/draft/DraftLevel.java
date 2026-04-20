package org.saiku.service.schema.generate.draft;

/**
 * Draft level. {@link Type} mirrors Mondrian level types we currently emit.
 *
 * <p>A level has either a {@link #column()} (plain column reference) or an {@link #expression()}
 * (SQL scalar expression evaluated against the fact row) — typically both are set for degenerate
 * time levels where {@code column} tracks the underlying source column and {@code expression}
 * carries the dialect-agnostic SQL function (e.g. {@code YEAR(order_date)}). The writer prefers the
 * expression when present, materialising a {@code CalculatedColumnDef} in the physical table.
 */
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
    private String expression;
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

    public String expression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
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
