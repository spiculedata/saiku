/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.service.schema.ossie.OssieYamlReader;
import org.saiku.service.schema.ossie.model.OssieDocument;

/**
 * Round-trip validation: the same YAML produced by {@code OssieYamlWriter} in saiku-service must
 * deserialise cleanly through the read side that saiku-sql owns. If this test breaks, either the
 * writer changed its shape (spec bump) or the reader forgot a {@code @JsonProperty} annotation on
 * a setter.
 */
public class OssieYamlReaderTest {

    private final OssieYamlReader reader = new OssieYamlReader();

    @Test
    public void minimalDocumentReadsBack() throws Exception {
        String yaml = "version: 0.2.0.dev0\n"
                + "semantic_model:\n"
                + "- name: Sales\n"
                + "  datasets:\n"
                + "  - name: orders\n"
                + "    source: public.orders\n"
                + "    primary_key:\n"
                + "    - order_id\n"
                + "  metrics:\n"
                + "  - name: total_revenue\n"
                + "    expression:\n"
                + "      dialects:\n"
                + "      - dialect: ANSI_SQL\n"
                + "        expression: SUM(orders.amount)\n";
        OssieDocument doc = reader.readString(yaml);
        assertEquals("0.2.0.dev0", doc.getVersion());
        assertEquals(1, doc.getSemanticModel().size());
        assertEquals("Sales", doc.getSemanticModel().get(0).getName());
        assertEquals(1, doc.getSemanticModel().get(0).getDatasets().size());
        assertEquals(
                "orders", doc.getSemanticModel().get(0).getDatasets().get(0).getName());
        assertEquals(
                "public.orders",
                doc.getSemanticModel().get(0).getDatasets().get(0).getSource());
        assertEquals(
                1,
                doc.getSemanticModel()
                        .get(0)
                        .getDatasets()
                        .get(0)
                        .getPrimaryKey()
                        .size());
        assertEquals(
                "order_id",
                doc.getSemanticModel()
                        .get(0)
                        .getDatasets()
                        .get(0)
                        .getPrimaryKey()
                        .get(0));
        assertEquals(1, doc.getSemanticModel().get(0).getMetrics().size());
        assertEquals(
                "total_revenue",
                doc.getSemanticModel().get(0).getMetrics().get(0).getName());
        assertEquals(
                "SUM(orders.amount)",
                doc.getSemanticModel()
                        .get(0)
                        .getMetrics()
                        .get(0)
                        .getExpression()
                        .getDialects()
                        .get(0)
                        .getExpression());
    }

    @Test
    public void aiContextAndCustomExtensionsReadBack() throws Exception {
        String yaml = "version: 0.2.0.dev0\n"
                + "semantic_model:\n"
                + "- name: T\n"
                + "  datasets:\n"
                + "  - name: dim\n"
                + "    source: s.dim\n"
                + "    fields:\n"
                + "    - name: L\n"
                + "      expression:\n"
                + "        dialects:\n"
                + "        - dialect: ANSI_SQL\n"
                + "          expression: c\n"
                + "      ai_context:\n"
                + "        instructions: A level.\n"
                + "        synonyms: [a, b]\n"
                + "      custom_extensions:\n"
                + "      - vendor_name: SAIKU\n"
                + "        data: '{\"pii\":true}'\n";
        OssieDocument doc = reader.readString(yaml);
        var field =
                doc.getSemanticModel().get(0).getDatasets().get(0).getFields().get(0);
        assertNotNull(field.getAiContext());
        assertEquals("A level.", field.getAiContext().getInstructions());
        assertEquals(2, field.getAiContext().getSynonyms().size());
        assertEquals(1, field.getCustomExtensions().size());
        assertEquals("SAIKU", field.getCustomExtensions().get(0).getVendorName());
        assertTrue(field.getCustomExtensions().get(0).getData().contains("pii"));
    }

    @Test
    public void relationshipsReadBack() throws Exception {
        String yaml = "version: 0.2.0.dev0\n"
                + "semantic_model:\n"
                + "- name: T\n"
                + "  datasets:\n"
                + "  - name: f\n"
                + "    source: s.f\n"
                + "  - name: d\n"
                + "    source: s.d\n"
                + "  relationships:\n"
                + "  - name: f_to_d\n"
                + "    from: f\n"
                + "    to: d\n"
                + "    from_columns:\n"
                + "    - fk\n"
                + "    to_columns:\n"
                + "    - pk\n";
        OssieDocument doc = reader.readString(yaml);
        var rel = doc.getSemanticModel().get(0).getRelationships().get(0);
        assertEquals("f_to_d", rel.getName());
        assertEquals("f", rel.getFrom());
        assertEquals("d", rel.getTo());
        assertEquals("fk", rel.getFromColumns().get(0));
        assertEquals("pk", rel.getToColumns().get(0));
    }

    @Test
    public void unknownPropertiesAreIgnored() throws Exception {
        // Additive spec fields we don't yet model shouldn't break the loader —
        // Ossie is a draft; downstream tools may add keys we haven't caught up on.
        String yaml = "version: 0.2.0.dev0\n"
                + "future_root_field: whatever\n"
                + "semantic_model:\n"
                + "- name: T\n"
                + "  future_model_field: whatever\n"
                + "  datasets:\n"
                + "  - name: d\n"
                + "    source: s.d\n"
                + "    future_dataset_field: whatever\n";
        OssieDocument doc = reader.readString(yaml);
        assertEquals("T", doc.getSemanticModel().get(0).getName());
    }
}
