/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import bi.saiku.ossie.OssieYamlWriter;
import bi.saiku.ossie.model.CustomExtension;
import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DimensionMeta;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Test;
import org.xml.sax.SAXException;

/**
 * Structural tests for the Mondrian → Ossie mapping. These aren't schema-validation tests
 * (the JSON-schema round-trip happens in {@code OssieYamlWriterTest}); they check that each Ossie
 * concept lands where the mapping table on saiku#1384 says it should — one dataset per fact +
 * dim, one relationship per dim, one metric per measure, is_time set from levelType=Time*, and
 * saiku.semantic.* annotations routed to ai_context vs custom_extensions per the spec's homes.
 */
public class MondrianToOssieConverterTest {

    private final MondrianToOssieConverter converter = new MondrianToOssieConverter();

    private final OssieYamlWriter writer = new OssieYamlWriter();

    @Test
    public void cubeBecomesSemanticModelWithFactAndDimensions()
            throws IOException, SAXException, ParserConfigurationException {
        String xml = "<?xml version='1.0'?>\n"
                + "<Schema name='Test'>\n"
                + "  <Cube name='Sales'>\n"
                + "    <Table name='fact_sales' schema='public'/>\n"
                + "    <Dimension name='Customer' foreignKey='customerkey'>\n"
                + "      <Hierarchy hasAll='true' primaryKey='customerkey'>\n"
                + "        <Table name='dim_customer' schema='public'/>\n"
                + "        <Level name='Region' column='region'/>\n"
                + "      </Hierarchy>\n"
                + "    </Dimension>\n"
                + "    <Measure name='Sales Amount' column='amount' aggregator='sum'/>\n"
                + "  </Cube>\n"
                + "</Schema>";
        OssieDocument doc = convert(xml);

        assertEquals(1, doc.getSemanticModel().size());
        SemanticModel sm = doc.getSemanticModel().get(0);
        assertEquals("Sales", sm.getName());
        assertEquals(2, sm.getDatasets().size());

        Dataset fact = sm.getDatasets().get(0);
        assertEquals("fact_sales", fact.getName());
        assertEquals("public.fact_sales", fact.getSource());

        Dataset customer = sm.getDatasets().get(1);
        assertEquals("Customer", customer.getName());
        assertEquals("public.dim_customer", customer.getSource());
        assertEquals(1, customer.getPrimaryKey().size());
        assertEquals("customerkey", customer.getPrimaryKey().get(0));
        assertEquals(1, customer.getFields().size());
        assertEquals("Region", customer.getFields().get(0).getName());
    }

    @Test
    public void relationshipCarriesForeignKeyPair() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='fact' schema='s'/>"
                + "<Dimension name='D' foreignKey='d_fk'>"
                + "  <Hierarchy primaryKey='d_pk'><Table name='dim_d' schema='s'/>"
                + "    <Level name='L' column='c'/></Hierarchy>"
                + "</Dimension></Cube></Schema>");
        SemanticModel sm = doc.getSemanticModel().get(0);
        assertEquals(1, sm.getRelationships().size());
        Relationship r = sm.getRelationships().get(0);
        assertEquals("fact", r.getFrom());
        assertEquals("D", r.getTo());
        assertEquals(1, r.getFromColumns().size());
        assertEquals("d_fk", r.getFromColumns().get(0));
        assertEquals(1, r.getToColumns().size());
        assertEquals("d_pk", r.getToColumns().get(0));
    }

    @Test
    public void measureEmitsBothAnsiSqlAndMdxDialects() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='fact'/>"
                + "<Measure name='Quantity' column='qty' aggregator='sum'/></Cube></Schema>");
        Metric m = doc.getSemanticModel().get(0).getMetrics().get(0);
        assertEquals(2, m.getExpression().getDialects().size());
        assertEquals("ANSI_SQL", m.getExpression().getDialects().get(0).getDialect());
        assertEquals("SUM(fact.qty)", m.getExpression().getDialects().get(0).getExpression());
        assertEquals("MDX", m.getExpression().getDialects().get(1).getDialect());
        assertEquals(
                "[Measures].[Quantity]", m.getExpression().getDialects().get(1).getExpression());
    }

    @Test
    public void distinctCountAggregatorTranslates() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Measure name='Uniques' column='u' aggregator='distinct-count'/></Cube></Schema>");
        Metric m = doc.getSemanticModel().get(0).getMetrics().get(0);
        assertEquals(
                "COUNT(DISTINCT f.u)", m.getExpression().getDialects().get(0).getExpression());
    }

    @Test
    public void calculatedMemberEmitsMdxOnly() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<CalculatedMember name='Ratio'>"
                + "  <Formula>[Measures].[A] / [Measures].[B]</Formula>"
                + "</CalculatedMember></Cube></Schema>");
        Metric m = doc.getSemanticModel().get(0).getMetrics().get(0);
        assertEquals(1, m.getExpression().getDialects().size());
        assertEquals("MDX", m.getExpression().getDialects().get(0).getDialect());
        assertEquals(
                "[Measures].[A] / [Measures].[B]",
                m.getExpression().getDialects().get(0).getExpression());
    }

    @Test
    public void levelTypeTimeYearsFlagsIsTime() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Dimension name='Date' foreignKey='dk'>"
                + "  <Hierarchy primaryKey='pk'><Table name='dim_date'/>"
                + "    <Level name='Year' column='y' levelType='TimeYears'/>"
                + "  </Hierarchy>"
                + "</Dimension></Cube></Schema>");
        Field year =
                doc.getSemanticModel().get(0).getDatasets().get(1).getFields().get(0);
        DimensionMeta dim = year.getDimension();
        assertNotNull(dim);
        assertTrue(dim.getIsTime());
    }

    @Test
    public void nonTimeLevelHasNoDimensionBlock() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Dimension name='Cust' foreignKey='fk'>"
                + "  <Hierarchy primaryKey='pk'><Table name='dim_c'/>"
                + "    <Level name='Region' column='r'/></Hierarchy></Dimension></Cube></Schema>");
        Field region =
                doc.getSemanticModel().get(0).getDatasets().get(1).getFields().get(0);
        assertNull("non-time levels shouldn't carry a dimension block", region.getDimension());
    }

    @Test
    public void descriptionAndSynonymsLiftIntoAiContext() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Measure name='M' column='c' aggregator='sum'>"
                + "  <Annotations>"
                + "    <Annotation name='saiku.semantic.description'>Total sold.</Annotation>"
                + "    <Annotation name='saiku.semantic.synonyms'>volume, quantity</Annotation>"
                + "  </Annotations>"
                + "</Measure></Cube></Schema>");
        Metric m = doc.getSemanticModel().get(0).getMetrics().get(0);
        assertNotNull(m.getAiContext());
        assertEquals("Total sold.", m.getAiContext().getInstructions());
        assertEquals(2, m.getAiContext().getSynonyms().size());
        assertEquals("volume", m.getAiContext().getSynonyms().get(0));
        assertEquals("quantity", m.getAiContext().getSynonyms().get(1));
        assertTrue(
                "no Saiku extension when only description+synonyms present",
                m.getCustomExtensions().isEmpty());
    }

    @Test
    public void piiAnnotationRidesInSaikuExtensionAsBoolean() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Dimension name='Prescriber' foreignKey='pk'>"
                + "  <Hierarchy primaryKey='pk'><Table name='dim_p'/>"
                + "    <Level name='Prescriber' column='name'>"
                + "      <Annotations>"
                + "        <Annotation name='saiku.semantic.pii'>true</Annotation>"
                + "      </Annotations>"
                + "    </Level></Hierarchy></Dimension></Cube></Schema>");
        Field f = doc.getSemanticModel().get(0).getDatasets().get(1).getFields().get(0);
        assertEquals(1, f.getCustomExtensions().size());
        CustomExtension ext = f.getCustomExtensions().get(0);
        assertEquals(CustomExtension.VENDOR_SAIKU, ext.getVendorName());
        // Payload is a JSON string per the Ossie spec.
        assertTrue(
                "pii should serialise as JSON boolean, not string",
                ext.getData().contains("\"pii\":true"));
    }

    @Test
    public void cardinalityAndGrainRideInSaikuExtensionAsStrings() throws Exception {
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                + "<Dimension name='D' foreignKey='fk'>"
                + "  <Hierarchy primaryKey='pk'><Table name='dim_d'/>"
                + "    <Level name='Year' column='y' levelType='TimeYears'>"
                + "      <Annotations>"
                + "        <Annotation name='saiku.semantic.cardinality'>low</Annotation>"
                + "        <Annotation name='saiku.semantic.grain'>year</Annotation>"
                + "      </Annotations>"
                + "    </Level></Hierarchy></Dimension></Cube></Schema>");
        Field year =
                doc.getSemanticModel().get(0).getDatasets().get(1).getFields().get(0);
        assertEquals(1, year.getCustomExtensions().size());
        String payload = year.getCustomExtensions().get(0).getData();
        assertTrue(payload.contains("\"cardinality\":\"low\""));
        assertTrue(payload.contains("\"grain\":\"year\""));
    }

    @Test
    public void multipleCubesBecomeMultipleSemanticModels() throws Exception {
        OssieDocument doc = convert("<Schema name='T'>"
                + "<Cube name='C1'><Table name='f1'/></Cube>"
                + "<Cube name='C2'><Table name='f2'/></Cube></Schema>");
        assertEquals(2, doc.getSemanticModel().size());
        assertEquals("C1", doc.getSemanticModel().get(0).getName());
        assertEquals("C2", doc.getSemanticModel().get(1).getName());
    }

    @Test
    public void degenerateDimensionAttachesLevelsToFactTable() throws Exception {
        // Degenerate dim: no <Table/> under the hierarchy → the levels resolve against the fact.
        OssieDocument doc = convert("<Schema name='T'><Cube name='C'><Table name='fact'/>"
                + "<Dimension name='Date' type='TimeDimension'>"
                + "  <Hierarchy hasAll='true'>"
                + "    <Level name='Year' column='order_year' levelType='TimeYears'/>"
                + "  </Hierarchy></Dimension></Cube></Schema>");
        SemanticModel sm = doc.getSemanticModel().get(0);
        // Only the fact dataset should exist — degenerate dim doesn't get its own.
        assertEquals(1, sm.getDatasets().size());
        Dataset fact = sm.getDatasets().get(0);
        // Level is added to the fact dataset directly.
        assertEquals(1, fact.getFields().size());
        assertEquals("Year", fact.getFields().get(0).getName());
        assertNotNull(fact.getFields().get(0).getDimension());
        // And there's no relationship — nothing to join to.
        assertTrue(sm.getRelationships().isEmpty());
    }

    @Test
    public void schemaWithZeroCubesEmitsEmptyDocument() throws Exception {
        OssieDocument doc = convert("<Schema name='T'></Schema>");
        assertTrue(doc.getSemanticModel().isEmpty());
        assertFalse("version should always be set", doc.getVersion().isBlank());
    }

    /* ==================================================================
     * saiku#1813 — Mondrian 4.
     *
     * Every MDX schema Saiku ships (FoodMart4, Bank, Pharma) uses this shape:
     * measures in a <MeasureGroup>, dimensions as <Attributes> + <Hierarchies>,
     * joins declared as <ForeignKeyLink> rather than a foreignKey attribute.
     * The classic-3 path recognised none of it, so `ossie-export` returned an
     * empty model for all three.
     * ================================================================== */

    /** The M4 shape, trimmed to what the converter has to read. */
    private static final String M4 = "<Schema name='P' metamodelVersion='4.0'>"
            + "<PhysicalSchema>"
            + "  <Table name='fact_rx'/>"
            + "  <Table name='dim_product'><Key><Column name='productkey'/></Key></Table>"
            + "  <Table name='dim_prescriber'><Key><Column name='prescriberkey'/></Key></Table>"
            + "</PhysicalSchema>"
            + "<Cube name='Rx'>"
            + "  <Dimensions>"
            + "    <Dimension name='Product' table='dim_product' key='Product Id'>"
            + "      <Attributes>"
            + "        <Attribute name='Product Id' keyColumn='productkey'/>"
            + "        <Attribute name='Brand' keyColumn='brand'/>"
            + "      </Attributes>"
            + "      <Hierarchies><Hierarchy name='Product'>"
            + "        <Level attribute='Brand'/>"
            + "      </Hierarchy></Hierarchies>"
            + "    </Dimension>"
            + "    <Dimension name='Prescriber' table='dim_prescriber' key='Prescriber'>"
            + "      <Attributes>"
            + "        <Attribute name='Prescriber' keyColumn='prescriberkey' nameColumn='prescribername'/>"
            + "      </Attributes>"
            + "      <Hierarchies><Hierarchy name='Prescriber'>"
            + "        <Level attribute='Prescriber'>"
            + "          <Annotations><Annotation name='saiku.semantic.pii'>true</Annotation></Annotations>"
            + "        </Level>"
            + "      </Hierarchy></Hierarchies>"
            + "    </Dimension>"
            + "  </Dimensions>"
            + "  <MeasureGroups><MeasureGroup name='Rx' table='fact_rx'>"
            + "    <Measures>"
            + "      <Measure name='Rx Count' column='rxkey' aggregator='distinct-count'/>"
            + "      <Measure name='Quantity' column='quantity' aggregator='sum'/>"
            + "    </Measures>"
            + "    <DimensionLinks>"
            + "      <ForeignKeyLink dimension='Product' foreignKeyColumn='productkey'/>"
            + "      <ForeignKeyLink dimension='Prescriber' foreignKeyColumn='prescriberkey'/>"
            + "    </DimensionLinks>"
            + "  </MeasureGroup></MeasureGroups>"
            + "</Cube></Schema>";

    @Test
    public void mondrian4CubeIsConvertedNotSkipped() throws Exception {
        OssieDocument doc = convert(M4);
        assertEquals(1, doc.getSemanticModel().size());
        assertTrue(
                "no cube should be skipped — got: " + converter.getSkippedCubes(),
                converter.getSkippedCubes().isEmpty());
    }

    @Test
    public void measureGroupTableBecomesTheFactDataset() throws Exception {
        SemanticModel sm = convert(M4).getSemanticModel().get(0);
        assertTrue(
                "expected a fact dataset named after the MeasureGroup's table",
                sm.getDatasets().stream().anyMatch(d -> "fact_rx".equals(d.getName())));
    }

    @Test
    public void measureGroupMeasuresBecomeMetrics() throws Exception {
        SemanticModel sm = convert(M4).getSemanticModel().get(0);
        assertEquals(
                java.util.List.of("Rx Count", "Quantity"),
                sm.getMetrics().stream().map(Metric::getName).toList());
    }

    @Test
    public void eachDimensionBecomesADatasetOfItsAttributes() throws Exception {
        SemanticModel sm = convert(M4).getSemanticModel().get(0);
        Dataset product = sm.getDatasets().stream()
                .filter(d -> "dim_product".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                java.util.List.of("Product Id", "Brand"),
                product.getFields().stream().map(Field::getName).toList());
    }

    @Test
    public void physicalSchemaKeyBecomesThePrimaryKey() throws Exception {
        SemanticModel sm = convert(M4).getSemanticModel().get(0);
        Dataset product = sm.getDatasets().stream()
                .filter(d -> "dim_product".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(java.util.List.of("productkey"), product.getPrimaryKey());
    }

    @Test
    public void foreignKeyLinkBecomesARelationship() throws Exception {
        SemanticModel sm = convert(M4).getSemanticModel().get(0);
        Relationship rel = sm.getRelationships().stream()
                .filter(r -> "dim_product".equals(r.getTo()))
                .findFirst()
                .orElseThrow();
        assertEquals("fact_rx", rel.getFrom());
        assertEquals(java.util.List.of("productkey"), rel.getFromColumns());
        assertEquals(java.util.List.of("productkey"), rel.getToColumns());
    }

    /**
     * The point of #1496, on the shape it was actually reported against. A PII
     * annotation sits on the <Level>, but the Ossie FIELD comes from the
     * <Attribute> the level references — so it has to be carried across, or the
     * flag is dropped exactly where it matters most.
     */
    @Test
    public void levelAnnotationsLandOnTheAttributesField() throws Exception {
        String yaml = writer.writeAsString(convert(M4));
        assertTrue(
                "expected the PII flag to survive onto the prescriber field — got:\n" + yaml,
                yaml.contains("vendor_name: SAIKU") && yaml.contains("pii"));
    }

    @Test
    public void noLinkDimensionGetsNoRelationship() throws Exception {
        String xml = M4.replace(
                "<ForeignKeyLink dimension='Prescriber' foreignKeyColumn='prescriberkey'/>",
                "<NoLink dimension='Prescriber'/>");
        SemanticModel sm = convert(xml).getSemanticModel().get(0);
        assertTrue(
                "a NoLink dimension must not produce a relationship",
                sm.getRelationships().stream().noneMatch(r -> "dim_prescriber".equals(r.getTo())));
    }

    @Test
    public void classic3StillWorks() throws Exception {
        // The M4 path must not capture a classic-3 cube.
        SemanticModel sm = convert("<Schema name='T'><Cube name='C'><Table name='f'/>"
                        + "<Measure name='M' column='c' aggregator='sum'/></Cube></Schema>")
                .getSemanticModel()
                .get(0);
        assertEquals(
                java.util.List.of("M"),
                sm.getMetrics().stream().map(Metric::getName).toList());
    }

    private OssieDocument convert(String xml) throws IOException, SAXException, ParserConfigurationException {
        return converter.convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
