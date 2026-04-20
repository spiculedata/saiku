package org.saiku.service.schema.generate.draft;

/**
 * Draft measure. {@code COUNT_STAR} represents the implicit fact-count measure (aggregator
 * "count" with no column).
 */
public class DraftMeasure {

    public enum Aggregator {
        SUM,
        COUNT,
        AVG,
        DISTINCT_COUNT,
        MIN,
        MAX,
        COUNT_STAR
    }

    private String name;
    private String column;
    private Aggregator aggregator;
    private Provenance provenance;

    public DraftMeasure(String name, String column, Aggregator aggregator, Provenance provenance) {
        this.name = name;
        this.column = column;
        this.aggregator = aggregator;
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

    public Aggregator aggregator() {
        return aggregator;
    }

    public void setAggregator(Aggregator aggregator) {
        this.aggregator = aggregator;
    }

    public Provenance provenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }
}
