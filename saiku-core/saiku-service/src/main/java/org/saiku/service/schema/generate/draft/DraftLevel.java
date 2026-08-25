/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
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
    /**
     * Optional physical table the {@link #column} lives on. {@code null} means the column belongs
     * to the owning dimension's primary {@code sourceTable}; non-null indicates a snowflake
     * lookup-side column and is emitted as the {@code table="..."} attribute on the Mondrian 4
     * Attribute element. See {@link org.saiku.service.schema.generate.writer.MondrianSchemaWriter}.
     */
    private String table;

    /**
     * Optional human-readable caption column (Mondrian {@code nameColumn}). When set, the writer
     * emits it on the level's Attribute so Saiku shows the string rather than the raw key id on
     * drillthrough / member listings. {@code null} means the level renders the key directly —
     * original behaviour. When non-null, {@link #column} is still the Attribute's {@code keyColumn}
     * (so distinct-member identity is preserved) and {@code nameColumn} is a second column on the
     * same physical table used purely for display.
     */
    private String nameColumn;

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

    public String table() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String nameColumn() {
        return nameColumn;
    }

    public void setNameColumn(String nameColumn) {
        this.nameColumn = nameColumn;
    }
}
