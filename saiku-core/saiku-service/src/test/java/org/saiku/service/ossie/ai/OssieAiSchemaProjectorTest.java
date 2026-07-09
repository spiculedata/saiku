/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;

/**
 * Unit tests for {@link OssieAiSchemaProjector} — no warehouse; sample-value population is
 * covered by the saiku-sql integration test where H2 is already spun up.
 */
public class OssieAiSchemaProjectorTest {

    @Test
    public void projectsBasicShape() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();
        OssieModelDto semantic = buildFixture();
        OssieAiSchema schema = p.project("Pharma", semantic, null);
        assertEquals("Pharma/Pharma", schema.getModelId());
        assertEquals("Pharma", schema.getConnectionName());
        assertEquals("Pharma", schema.getModelName());
        // Datasets keyed lower-case; PII field dropped.
        assertTrue(schema.getDatasets().containsKey("customers"));
        assertFalse(schema.getDatasets().get("customers").getFields().containsKey("ssn"));
        assertTrue(schema.getDatasets().get("customers").getFields().containsKey("region"));
        // Metrics keep their case.
        assertNotNull(schema.getMetrics().get("net_revenue"));
        assertNotNull(schema.getMetrics().get("line_count"));
    }

    @Test
    public void supportedOverridesReflectsCountStarEdgeCase() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();
        OssieAiSchema schema = p.project("Pharma", buildFixture(), null);
        // net_revenue (SUM(...)) accepts all 5 overrides.
        assertEquals(
                List.of("SUM", "AVG", "MIN", "MAX", "COUNT"),
                schema.getMetrics().get("net_revenue").getSupportedOverrides());
        // line_count (COUNT(*)) only accepts COUNT.
        assertEquals(List.of("COUNT"), schema.getMetrics().get("line_count").getSupportedOverrides());
    }

    @Test
    public void deriveAggregationKindReadsExpressionOrDeclaration() {
        OssieModelDto.Metric declared = new OssieModelDto.Metric();
        declared.setAggregationKind("SUM");
        declared.setExpression("something unrelated");
        assertEquals("sum", OssieAiSchemaProjector.deriveAggregationKind(declared));

        OssieModelDto.Metric fromExpr = new OssieModelDto.Metric();
        fromExpr.setExpression("AVG(x)");
        assertEquals("avg", OssieAiSchemaProjector.deriveAggregationKind(fromExpr));

        OssieModelDto.Metric none = new OssieModelDto.Metric();
        none.setExpression("weird_udf(x)");
        assertNull(OssieAiSchemaProjector.deriveAggregationKind(none));
    }

    @Test
    public void factDatasetHeuristicPicksFactPrefix() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();
        OssieAiSchema schema = p.project("Pharma", buildFixture(), null);
        assertEquals("fact_pharma", schema.getFactDataset());
    }

    @Test
    public void generatesReadyMadeExamples() {
        OssieAiSchemaProjector p = new OssieAiSchemaProjector();
        OssieAiSchema schema = p.project("Pharma", buildFixture(), null);
        assertNotNull(schema.getExamples().get("simpleGroupBy"));
        assertNotNull(schema.getExamples().get("topN"));
        // The simpleGroupBy body references the model + a real dataset + a real metric.
        OssieAiQueryRequest simple = schema.getExamples().get("simpleGroupBy").getBody();
        assertEquals("Pharma", simple.getModel());
        assertFalse(simple.getRows().isEmpty());
        assertFalse(simple.getValues().isEmpty());
    }

    // ---- alias map + custom_extensions passthrough (#1408 / #1409) ----

    @Test
    public void aliasMapsCarryThroughFromDto() {
        OssieModelDto semantic = buildFixture();
        semantic.getFieldAliases().put("state", "customers.region");
        semantic.getMetricAliases().put("revenue", "net_revenue");
        semantic.getDatasetAliases().put("clients", "customers");

        OssieAiSchema schema = new OssieAiSchemaProjector().project("Pharma", semantic, null);
        assertEquals("customers.region", schema.getFieldAliases().get("state"));
        assertEquals("net_revenue", schema.getMetricAliases().get("revenue"));
        assertEquals("customers", schema.getDatasetAliases().get("clients"));
    }

    @Test
    public void customExtensionsCarryThroughOnField() {
        OssieModelDto semantic = buildFixture();
        // Attach an ext to the fact_pharma.NETREVENUE field.
        org.saiku.service.ossie.CustomExtensionDto ext = new org.saiku.service.ossie.CustomExtensionDto();
        ext.setVendorName("SAIKU");
        semantic.getDatasets().get(0).getFields().get(0).getCustomExtensions().add(ext);

        OssieAiSchema schema = new OssieAiSchemaProjector().project("Pharma", semantic, null);
        var field = schema.getDatasets().get("fact_pharma").getFields().get("netrevenue");
        assertEquals(1, field.getCustomExtensions().size());
        assertEquals("SAIKU", field.getCustomExtensions().get(0).getVendorName());
    }

    @Test
    public void customExtensionsCarryThroughOnDatasetAndMetric() {
        OssieModelDto semantic = buildFixture();
        org.saiku.service.ossie.CustomExtensionDto dsExt = new org.saiku.service.ossie.CustomExtensionDto();
        dsExt.setVendorName("SAIKU");
        semantic.getDatasets().get(0).getCustomExtensions().add(dsExt);

        org.saiku.service.ossie.CustomExtensionDto mExt = new org.saiku.service.ossie.CustomExtensionDto();
        mExt.setVendorName("DBT");
        semantic.getMetrics().get(0).getCustomExtensions().add(mExt);

        OssieAiSchema schema = new OssieAiSchemaProjector().project("Pharma", semantic, null);
        assertEquals(
                1, schema.getDatasets().get("fact_pharma").getCustomExtensions().size());
        assertEquals(
                "DBT",
                schema.getMetrics()
                        .get("net_revenue")
                        .getCustomExtensions()
                        .get(0)
                        .getVendorName());
    }

    private OssieModelDto buildFixture() {
        OssieModelDto semantic = new OssieModelDto();
        semantic.setName("Pharma");
        semantic.setDescription("Pharma sales fixture");

        OssieModelDto.Dataset fact = new OssieModelDto.Dataset();
        fact.setName("fact_pharma");
        fact.setSource("FACT_PHARMA");
        OssieModelDto.Field netrev = new OssieModelDto.Field();
        netrev.setName("NETREVENUE");
        fact.getFields().add(netrev);
        semantic.getDatasets().add(fact);

        OssieModelDto.Dataset customers = new OssieModelDto.Dataset();
        customers.setName("customers");
        customers.setSource("DIM_CUSTOMERS");
        OssieModelDto.Field region = new OssieModelDto.Field();
        region.setName("REGION");
        customers.getFields().add(region);
        // PII field should be stripped from the AI view (saiku#902 parity).
        OssieModelDto.Field ssn = new OssieModelDto.Field();
        ssn.setName("SSN");
        ssn.setPii(true);
        customers.getFields().add(ssn);
        semantic.getDatasets().add(customers);

        OssieModelDto.Metric netRev = new OssieModelDto.Metric();
        netRev.setName("net_revenue");
        netRev.setExpression("SUM(\"fact_pharma\".\"NETREVENUE\")");
        semantic.getMetrics().add(netRev);

        OssieModelDto.Metric lineCount = new OssieModelDto.Metric();
        lineCount.setName("line_count");
        lineCount.setExpression("COUNT(*)");
        semantic.getMetrics().add(lineCount);
        return semantic;
    }
}
