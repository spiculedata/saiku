package org.saiku.semantic.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Top-level semantic layer IR. One {@code CubeDefinition} compiles to one
 * {@code <Schema><Cube>...</Cube></Schema>} Mondrian document.
 */
public record CubeDefinition(
        String name, FactTable fact, List<Dimension> dimensions, List<Measure> measures) {

    @JsonCreator
    public CubeDefinition(
            @JsonProperty("name") String name,
            @JsonProperty("fact") FactTable fact,
            @JsonProperty("dimensions") List<Dimension> dimensions,
            @JsonProperty("measures") List<Measure> measures) {
        this.name = name;
        this.fact = fact;
        this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        this.measures = measures == null ? List.of() : List.copyOf(measures);
    }

    public record FactTable(@JsonProperty("table") String table, @JsonProperty("schema") String schema) {}
}
