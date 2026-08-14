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
import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.SemanticModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * saiku#1813 — the Mondrian 4 round-trip, against a slice taken VERBATIM from the shipped
     * FoodMart4 schema and checked into test resources.
     *
     * <p>This replaces a check that read {@code ../../saiku-home/data/FoodMart4.xml} and returned
     * early when absent — the same gitignored-runtime-file trap as #1808: silent on CI, and a
     * different test on every developer machine.
     *
     * <p>The slice is not invented. It keeps the Store dimension precisely because that is where
     * the real schema broke the first cut of the M4 reader: its attributes declare columns as
     * {@code <Key>} / {@code <Name>} CHILD ELEMENTS rather than {@code keyColumn} /
     * {@code nameColumn} attributes, and {@code Store City} keys on a COMPOUND
     * {@code store_state + store_city}. Reading only the attribute form left those fields with no
     * {@code expression}, which Ossie's schema rejects — and no hand-written fixture caught it.
     */
    @Test
    public void foodmart4SliceEmitsValidOssie() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ossie/foodmart4-slice.xml")) {
            assertNotNull("foodmart4-slice.xml must be on the test classpath", in);
            OssieDocument doc = converter.convert(in);
            assertTrue(
                    "the slice must convert, not be skipped",
                    !doc.getSemanticModel().isEmpty());
            assertValidatesAgainstOssieSchema(writer.writeAsString(doc));
        }
    }

    @Test
    public void foodmart4SliceGivesEveryFieldAnExpression() throws Exception {
        // The specific defect the real schema exposed. Asserted directly so a regression names
        // itself rather than surfacing as an opaque Ossie validation failure.
        try (InputStream in = getClass().getResourceAsStream("/ossie/foodmart4-slice.xml")) {
            OssieDocument doc = converter.convert(in);
            for (SemanticModel sm : doc.getSemanticModel()) {
                for (Dataset ds : sm.getDatasets()) {
                    for (Field f : ds.getFields()) {
                        assertNotNull(
                                "field '" + f.getName() + "' on dataset '" + ds.getName()
                                        + "' has no expression — Ossie requires one",
                                f.getExpression());
                    }
                }
            }
        }
    }

    @Test
    public void noLinkProducesNoRelationship() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ossie/foodmart4-slice.xml")) {
            OssieDocument doc = converter.convert(in);
            SemanticModel sm = doc.getSemanticModel().get(0);
            assertTrue(
                    "a <NoLink> dimension must not produce a relationship",
                    sm.getRelationships().stream().noneMatch(r -> "Nonexistent".equals(r.getTo())));
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
     * saiku#1808 / saiku#1813 — the Pharma check used to read a GITIGNORED
     * runtime file (../../saiku-home/data/Pharma.xml) and return early when it
     * was absent: a permanent green on CI, a hard failure on any developer
     * machine with a materialised home.
     *
     * Its assertion also misdescribed the failure. It read "expected a SAIKU
     * vendor extension carrying pii=true", which points at the annotation path,
     * when the cause was that the converter did not recognise the cube AT ALL —
     * every MDX schema Saiku ships uses the Mondrian 4 <MeasureGroup> shape.
     *
     * #1813 added that support, so the assertion can come back — against a
     * checked-in fixture that runs identically everywhere.
     * ==================================================================== */

    /** The Mondrian 4 shape, with PII annotated where a schema author puts it: on the LEVEL. */
    private static final String MONDRIAN_4_SCHEMA = "<Schema name='M4' metamodelVersion='4.0'>"
            + "<PhysicalSchema>"
            + "  <Table name='rx_fact'/>"
            + "  <Table name='dim_prescriber'><Key><Column name='prescriberkey'/></Key></Table>"
            + "</PhysicalSchema>"
            + "<Cube name='Rx'>"
            + "  <Dimensions><Dimension name='Prescriber' table='dim_prescriber' key='Prescriber'>"
            + "    <Attributes>"
            + "      <Attribute name='Prescriber' keyColumn='prescriberkey' nameColumn='prescribername'/>"
            + "    </Attributes>"
            + "    <Hierarchies><Hierarchy name='Prescriber'>"
            + "      <Level attribute='Prescriber'>"
            + "        <Annotations><Annotation name='saiku.semantic.pii'>true</Annotation></Annotations>"
            + "      </Level>"
            + "    </Hierarchy></Hierarchies>"
            + "  </Dimension></Dimensions>"
            + "  <MeasureGroups><MeasureGroup name='Rx' table='rx_fact'>"
            + "    <Measures><Measure name='Scripts' column='script_count' aggregator='sum'/></Measures>"
            + "    <DimensionLinks>"
            + "      <ForeignKeyLink dimension='Prescriber' foreignKeyColumn='prescriberkey'/>"
            + "    </DimensionLinks>"
            + "  </MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";

    @Test
    public void mondrian4SchemaConvertsAndPreservesPii() throws Exception {
        OssieDocument doc = convert(MONDRIAN_4_SCHEMA);
        assertTrue(
                "the M4 cube must convert, not be skipped",
                !doc.getSemanticModel().isEmpty());
        assertTrue(
                "no cube should be skipped — got: " + converter.getSkippedCubes(),
                converter.getSkippedCubes().isEmpty());

        String yaml = writer.writeAsString(doc);
        assertValidatesAgainstOssieSchema(yaml);
        // The annotation sits on the <Level>; the FIELD comes from the <Attribute> the level
        // references, so this only passes if the flag is carried across.
        assertTrue(
                "expected the PII flag to survive — got:\n" + yaml,
                yaml.contains("vendor_name: SAIKU") && yaml.contains("pii"));
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
