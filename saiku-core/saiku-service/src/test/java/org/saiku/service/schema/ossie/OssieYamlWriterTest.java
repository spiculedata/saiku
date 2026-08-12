/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bi.saiku.ossie.OssieYamlWriter;
import bi.saiku.ossie.model.OssieDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;

/**
 * Round-trip validation: build a Mondrian schema fragment, run the converter, serialise via
 * {@link OssieYamlWriter}, re-parse the YAML, and validate against apache/ossie's shipped {@code
 * osi-schema.json}.
 *
 * <p>Runs against three fixtures: the tiny inline schema (fast baseline), the real FoodMart schema
 * bundled with the launcher (the annotation-rich, dim-heavy shape), and the Pharma schema (PII
 * annotations + degenerate time dim). Each fixture must produce YAML that:
 *
 * <ul>
 *   <li>Deserialises cleanly through Jackson-YAML.
 *   <li>Validates against the pinned Ossie draft schema with zero findings.
 *   <li>Round-trips through the writer + parser without content drift.
 * </ul>
 */
public class OssieYamlWriterTest {

    private final OssieYamlWriter writer = new OssieYamlWriter();
    private final MondrianToOssieConverter converter = new MondrianToOssieConverter();

    @Test
    public void inlineSchemaEmitsValidOssie() throws Exception {
        String yaml = writer.writeAsString(converter.convert(new java.io.ByteArrayInputStream(("<Schema name='T'>"
                        + "<Cube name='Sales'>"
                        + "  <Table name='fact' schema='public'/>"
                        + "  <Dimension name='Cust' foreignKey='fk'>"
                        + "    <Hierarchy primaryKey='pk'><Table name='dim_c' schema='public'/>"
                        + "      <Level name='Region' column='r'/></Hierarchy></Dimension>"
                        + "  <Measure name='Amount' column='amt' aggregator='sum'/>"
                        + "</Cube></Schema>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertValidatesAgainstOssieSchema(yaml);
        // Sanity: contains the expected structural anchors.
        assertTrue(yaml.contains("semantic_model"));
        assertTrue(yaml.contains("datasets"));
        assertTrue(yaml.contains("Sales"));
        assertTrue(yaml.contains("ANSI_SQL"));
        assertTrue(yaml.contains("MDX"));
    }

    @Test
    public void foodmartSchemaEmitsValidOssie() throws Exception {
        Path schema = Path.of(System.getProperty("user.dir"))
                .resolve("../../saiku-home/data/FoodMart4.xml")
                .normalize();
        if (!Files.exists(schema)) {
            // Local dev only; not on CI unless the launcher module is checked out alongside.
            return;
        }
        try (InputStream in = Files.newInputStream(schema)) {
            OssieDocument doc = converter.convert(in);
            String yaml = writer.writeAsString(doc);
            // The important assertion is that whatever comes out validates against Ossie's schema.
            // FoodMart uses Mondrian 4's <Dimensions>/<MeasureGroups>/<Measures> wrapper shape
            // which the first-cut converter (saiku#1384 slice 1) doesn't yet parse — those cubes
            // get skipped (see MondrianToOssieConverter#skippedCubes) rather than emitted as
            // schema-invalid stubs. The Mondrian-4-MG follow-up (saiku#TBD) will populate them.
            assertValidatesAgainstOssieSchema(yaml);
        }
    }

    /**
     * saiku#1496 — PII flags must survive the Mondrian → Ossie YAML export end-to-end
     * (converter attaches the SAIKU custom_extension, the writer must serialise it).
     *
     * <p>Uses an INLINE PII-bearing fixture so this runs on every CI checkout. The previous
     * version read the untracked {@code saiku-home/data/Pharma.xml} and early-returned when
     * absent — a silent no-op on CI that gave false confidence while the export was (per the
     * 4.6.0 release verify) dropping the extensions entirely.
     */
    @Test
    public void piiAnnotationSurvivesExportToYaml() throws Exception {
        String schemaXml = "<Schema name='Pharma'>"
                + "<Cube name='Rx'>"
                + "  <Table name='rx_fact' schema='public'/>"
                + "  <Dimension name='Prescriber' foreignKey='prescriber_id'>"
                + "    <Hierarchy primaryKey='id'><Table name='prescriber' schema='public'/>"
                + "      <Level name='Name' column='full_name'>"
                + "        <Annotations>"
                + "          <Annotation name='saiku.semantic.pii'>true</Annotation>"
                + "        </Annotations>"
                + "      </Level>"
                + "    </Hierarchy></Dimension>"
                + "  <Measure name='Scripts' column='script_count' aggregator='count'/>"
                + "</Cube></Schema>";
        OssieDocument doc = converter.convert(
                new java.io.ByteArrayInputStream(schemaXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String yaml = writer.writeAsString(doc);
        assertValidatesAgainstOssieSchema(yaml);
        assertTrue(
                "expected a SAIKU vendor extension in the emitted YAML — got:\n" + yaml,
                yaml.contains("vendor_name: SAIKU"));
        assertTrue(
                "expected the extension payload to carry pii=true — got:\n" + yaml,
                yaml.contains("pii") && yaml.contains("true"));
    }

    /** Kept as an opt-in deep check against the full Pharma schema when the runtime file exists
     *  locally; the inline fixture above is the CI gate. */
    @Test
    public void pharmaSchemaEmitsValidOssieAndPreservesPii() throws Exception {
        Path schema = Path.of(System.getProperty("user.dir"))
                .resolve("../../saiku-home/data/Pharma.xml")
                .normalize();
        if (!Files.exists(schema)) {
            return;
        }
        try (InputStream in = Files.newInputStream(schema)) {
            OssieDocument doc = converter.convert(in);
            String yaml = writer.writeAsString(doc);
            assertValidatesAgainstOssieSchema(yaml);
            // Pharma marks Prescriber name + NPI PII. Both should surface in the emitted YAML as
            // custom_extensions with vendor_name=SAIKU and a JSON payload naming pii=true.
            assertTrue(
                    "expected at least one SAIKU vendor extension carrying pii=true",
                    yaml.contains("vendor_name: SAIKU") && yaml.contains("pii"));
        }
    }

    @Test
    public void writerRoundTripsThroughYamlParser() throws Exception {
        String yaml = writer.writeAsString(converter.convert(
                new java.io.ByteArrayInputStream("<Schema><Cube name='C'><Table name='f'/></Cube></Schema>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode tree = yamlMapper.readTree(yaml);
        assertNotNull(tree.get("version"));
        assertEquals(OssieDocument.OSSIE_SPEC_VERSION, tree.get("version").asText());
        assertNotNull(tree.get("semantic_model"));
        assertEquals(1, tree.get("semantic_model").size());
        assertEquals("C", tree.get("semantic_model").get(0).get("name").asText());
    }

    /* ---------------- helpers ---------------- */

    private void assertValidatesAgainstOssieSchema(String yaml) throws IOException {
        // Parse YAML → JsonNode via Jackson-YAML.
        JsonNode instance = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        JsonSchema schema = loadOssieSchema();
        Set<ValidationMessage> messages = schema.validate(instance);
        if (!messages.isEmpty()) {
            StringBuilder sb = new StringBuilder("Ossie schema validation failed:\n");
            for (ValidationMessage m : messages) sb.append("  - ").append(m).append('\n');
            sb.append("\nOffending YAML:\n").append(yaml);
            fail(sb.toString());
        }
    }

    private JsonSchema loadOssieSchema() throws IOException {
        try (InputStream schemaStream = getClass().getResourceAsStream("/ossie/osi-schema.json")) {
            if (schemaStream == null) {
                fail("Missing test resource: /ossie/osi-schema.json — copy from apache/ossie repo.");
            }
            // Ossie's schema declares $schema=draft/2020-12 — must instantiate the factory with
            // the matching dialect otherwise networknt falls back to draft-07 and misses features.
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaStream);
        }
    }
}
