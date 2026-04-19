package org.saiku.semantic.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Level(String name, String column, String type, String nameColumn, String ordinalColumn) {

    @JsonCreator
    public Level(
            @JsonProperty("name") String name,
            @JsonProperty("column") String column,
            @JsonProperty("type") String type,
            @JsonProperty("nameColumn") String nameColumn,
            @JsonProperty("ordinalColumn") String ordinalColumn) {
        this.name = name;
        this.column = column;
        this.type = type;
        this.nameColumn = nameColumn;
        this.ordinalColumn = ordinalColumn;
    }
}
