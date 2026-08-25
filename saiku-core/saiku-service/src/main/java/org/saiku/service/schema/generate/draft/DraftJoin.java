/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.draft;

/** One-hop snowflake join between a hierarchy's primary table and a lookup table. */
public class DraftJoin {

    private String leftTable;
    private String leftKey;
    private String rightTable;
    private String rightKey;

    public DraftJoin(String leftTable, String leftKey, String rightTable, String rightKey) {
        this.leftTable = leftTable;
        this.leftKey = leftKey;
        this.rightTable = rightTable;
        this.rightKey = rightKey;
    }

    public String leftTable() {
        return leftTable;
    }

    public void setLeftTable(String leftTable) {
        this.leftTable = leftTable;
    }

    public String leftKey() {
        return leftKey;
    }

    public void setLeftKey(String leftKey) {
        this.leftKey = leftKey;
    }

    public String rightTable() {
        return rightTable;
    }

    public void setRightTable(String rightTable) {
        this.rightTable = rightTable;
    }

    public String rightKey() {
        return rightKey;
    }

    public void setRightKey(String rightKey) {
        this.rightKey = rightKey;
    }
}
