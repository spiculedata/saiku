/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * saiku#1434 — the Ossie parser must handle both envelope shapes the shipped fixtures use, and
 * map datasets/fields/metrics onto the same element kinds the Mondrian parser produces so one
 * diff engine covers both.
 */
class OssieYamlModelParserTest {

    private final OssieYamlModelParser parser = new OssieYamlModelParser();

    /** The tpcds.ossie.yaml shape: semantic_model is a LIST. */
    private static final String LIST_ENVELOPE = "version: 0.2.0.dev0\n"
            + "semantic_model:\n"
            + "- name: TPCDS\n"
            + "  datasets:\n"
            + "  - name: store_sales\n"
            + "    source: STORE_SALES\n"
            + "    fields:\n"
            + "    - name: ss_quantity\n"
            + "      label: Quantity\n"
            + "  metrics:\n"
            + "  - name: total_sales\n"
            + "    aggregation_kind: sum\n";

    /** The flights.ossie.yaml shape: semantic_model nested under ontology_mappings. */
    private static final String ONTOLOGY_ENVELOPE = "version: 0.2.0.dev0\n"
            + "name: flights\n"
            + "ontology_mappings:\n"
            + "- name: flights_map\n"
            + "  semantic_model:\n"
            + "    name: Flights\n"
            + "    datasets:\n"
            + "    - name: flight\n"
            + "      fields:\n"
            + "      - name: dep_delay_min\n"
            + "    metrics:\n"
            + "    - name: flight_count\n";

    @Test
    void readsSemanticModelList() {
        ModelSnapshot snapshot = parser.parse(LIST_ENVELOPE, "tpcds.ossie.yaml");
        assertEquals(ModelFormat.OSSIE_YAML, snapshot.format());
        assertEquals("TPCDS", snapshot.name());
        assertTrue(snapshot.hasCube("TPCDS"));
        assertTrue(snapshot.hasMeasure("TPCDS", "total_sales"));
        assertTrue(snapshot.hasDimension("TPCDS", "store_sales"));
        assertTrue(snapshot.hasLevel("TPCDS", "store_sales", "ss_quantity"));
    }

    @Test
    void readsOntologyMappingsEnvelope() {
        ModelSnapshot snapshot = parser.parse(ONTOLOGY_ENVELOPE, "flights.ossie.yaml");
        assertEquals("flights", snapshot.name());
        assertTrue(snapshot.hasCube("Flights"));
        assertTrue(snapshot.hasMeasure("Flights", "flight_count"));
        assertTrue(snapshot.hasLevel("Flights", "flight", "dep_delay_min"));
    }

    @Test
    void metricsDiffAsMeasuresAndFieldsAsLevels() {
        // The cross-format element mapping is what lets one report speak for both serialisations.
        ModelSnapshot ossie = parser.parse(LIST_ENVELOPE, "tpcds.ossie.yaml");
        assertEquals(1, ossie.members("TPCDS", ModelElementKind.MEASURE).size());
        assertEquals(1, ossie.members("TPCDS", ModelElementKind.LEVEL).size());
    }

    @Test
    void caseInsensitiveLookup() {
        ModelSnapshot snapshot = parser.parse(LIST_ENVELOPE, "tpcds.ossie.yaml");
        assertTrue(snapshot.hasMeasure("tpcds", "TOTAL_SALES"));
    }

    @Test
    void modelWithoutSemanticModelIsRejected() {
        ModelDiffException e =
                assertThrows(ModelDiffException.class, () -> parser.parse("version: 1.0\nname: empty\n", "empty.yaml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void malformedYamlIsRejected() {
        ModelDiffException e = assertThrows(
                ModelDiffException.class, () -> parser.parse("semantic_model: [\n  - name: x\n", "bad.yaml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void emptyPayloadIsRejected() {
        ModelDiffException e = assertThrows(ModelDiffException.class, () -> parser.parse("   ", "blank.yaml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void nonMappingRootIsRejected() {
        ModelDiffException e =
                assertThrows(ModelDiffException.class, () -> parser.parse("- just\n- a\n- list\n", "list.yaml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void renamingAMetricKeepsItsFingerprint() {
        String renamed = LIST_ENVELOPE.replace("name: total_sales", "name: revenue");
        ModelElement before = parser.parse(LIST_ENVELOPE, "before.yaml")
                .find(ModelElementKind.MEASURE, "TPCDS", null, "total_sales")
                .orElseThrow();
        ModelElement after = parser.parse(renamed, "after.yaml")
                .find(ModelElementKind.MEASURE, "TPCDS", null, "revenue")
                .orElseThrow();
        assertEquals(before.signature(), after.signature());
    }

    @Test
    void changingAMetricDefinitionChangesItsFingerprint() {
        String changed = LIST_ENVELOPE.replace("aggregation_kind: sum", "aggregation_kind: avg");
        ModelElement before = parser.parse(LIST_ENVELOPE, "before.yaml")
                .find(ModelElementKind.MEASURE, "TPCDS", null, "total_sales")
                .orElseThrow();
        ModelElement after = parser.parse(changed, "after.yaml")
                .find(ModelElementKind.MEASURE, "TPCDS", null, "total_sales")
                .orElseThrow();
        assertFalse(before.signature().equals(after.signature()));
    }
}
