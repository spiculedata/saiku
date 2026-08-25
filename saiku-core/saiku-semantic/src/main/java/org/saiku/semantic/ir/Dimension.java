/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.semantic.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Dimension(String name, String table, String foreignKey, String primaryKey, List<Level> levels) {

    @JsonCreator
    public Dimension(
            @JsonProperty("name") String name,
            @JsonProperty("table") String table,
            @JsonProperty("foreignKey") String foreignKey,
            @JsonProperty("primaryKey") String primaryKey,
            @JsonProperty("levels") List<Level> levels) {
        this.name = name;
        this.table = table;
        this.foreignKey = foreignKey;
        this.primaryKey = primaryKey;
        this.levels = levels == null ? List.of() : List.copyOf(levels);
    }
}
