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

    /* ====================================================================
     * saiku#1808 — what used to live here was an "opt-in deep check" that read
     * ../../saiku-home/data/Pharma.xml, a GITIGNORED runtime file, and returned
     * early when it was absent. That made it two different tests:
     *
     *   - on CI, where no saiku-home exists, it passed without asserting
     *     anything at all — a permanent green;
     *   - on a developer machine with a materialised home, it ran and FAILED,
     *     so `mvn verify` was red locally and green on CI, and the failure
     *     looked like whatever you happened to be working on.
     *
     * The failure was real but the assertion misdescribed it. Every MDX schema
     * Saiku ships — FoodMart4, Bank AND Pharma — uses the Mondrian 4
     * <MeasureGroup> shape, which this converter deliberately does not handle
     * yet, so it skips the cube and emits an empty model. The test reported
     * that as "PII flags missing", which sent you looking at the annotation
     * path (the subject of #1496) instead of at cube recognition.
     *
     * PII propagation is already covered on CI by
     * piiAnnotationSurvivesExportToYaml() above, against an inline classic-3
     * fixture. So the useful test here is not another PII assertion — it is
     * pinning the M4 behaviour itself, which nothing covered.
     * ==================================================================== */

    /** The Mondrian 4 shape, reduced to the part the converter trips on: measures live in a
     *  {@code <MeasureGroup>} rather than directly on the {@code <Cube>}. */
    private static final String MONDRIAN_4_SCHEMA = "<Schema name='M4' metamodelVersion='4.0'>"
            + "<PhysicalSchema><Table name='rx_fact'/></PhysicalSchema>"
            + "<Cube name='Rx'>"
            + "  <Dimensions><Dimension name='Prescriber' table='dim_prescriber' key='Prescriber'>"
            + "    <Attributes><Attribute name='Prescriber' keyColumn='prescriberkey'/></Attributes>"
            + "  </Dimension></Dimensions>"
            + "  <MeasureGroups><MeasureGroup name='Rx' table='rx_fact'>"
            + "    <Measures><Measure name='Scripts' column='script_count' aggregator='sum'/></Measures>"
            + "  </MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";

    @Test
    public void mondrian4CubeIsSkippedAndReported() throws Exception {
        OssieDocument doc = convert(MONDRIAN_4_SCHEMA);

        // The gap itself: no semantic model comes out...
        assertTrue(
                "expected the M4 cube to produce no semantic model — got "
                        + doc.getSemanticModel().size(),
                doc.getSemanticModel().isEmpty());
        // ...but it must be REPORTED, not silently dropped. This is the only
        // signal `saiku ossie-export` has to tell an operator why their YAML is
        // empty, so it is the part that must not regress.
        assertTrue(
                "the skipped cube must be reported by name — got: " + converter.getSkippedCubes(),
                converter.getSkippedCubes().contains("Rx"));
    }

    @Test
    public void mondrian4OutputStillValidatesAsOssie() throws Exception {
        // An empty model is a legitimate Ossie document; emitting something
        // schema-invalid would be worse than emitting nothing.
        assertValidatesAgainstOssieSchema(writer.writeAsString(convert(MONDRIAN_4_SCHEMA)));
    }

    @Test
    public void classic3CubeIsNotReportedAsSkipped() throws Exception {
        // Guards the inverse: whatever makes M4 skip must not catch a shape the
        // converter genuinely handles.
        convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Measure name='M' column='c' aggregator='sum'/></Cube></Schema>");
        assertTrue(
                "a classic-3 cube must not be reported skipped — got: " + converter.getSkippedCubes(),
                converter.getSkippedCubes().isEmpty());
    }

    private OssieDocument convert(String schemaXml) throws Exception {
        return converter.convert(
                new java.io.ByteArrayInputStream(schemaXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
