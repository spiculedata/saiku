/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link OssieAiValidator}. Uses a hand-built {@link OssieAiSchema} so the
 * projector isn't in scope — validators should work over any schema shape.
 */
public class OssieAiValidatorTest {

    private OssieAiValidator validator;
    private OssieAiSchema schema;

    @Before
    public void setUp() {
        validator = new OssieAiValidator();
        schema = new OssieAiSchema();
        schema.setModelName("Pharma");

        OssieAiSchema.Dataset customers = new OssieAiSchema.Dataset();
        customers.setName("customers");
        OssieAiSchema.Field region = new OssieAiSchema.Field();
        region.setName("region");
        customers.getFields().put("region", region);
        OssieAiSchema.Field state = new OssieAiSchema.Field();
        state.setName("state");
        customers.getFields().put("state", state);
        schema.getDatasets().put("customers", customers);

        OssieAiSchema.Metric netRev = new OssieAiSchema.Metric();
        netRev.setName("net_revenue");
        netRev.setSupportedOverrides(List.of("SUM", "AVG", "MIN", "MAX", "COUNT"));
        schema.getMetrics().put("net_revenue", netRev);

        OssieAiSchema.Metric lineCount = new OssieAiSchema.Metric();
        lineCount.setName("line_count");
        lineCount.setSupportedOverrides(List.of("COUNT"));
        schema.getMetrics().put("line_count", lineCount);
    }

    @Test
    public void validRequestPasses() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        req.getValues().add(metricRef("net_revenue", null));
        validator.validate(req, schema);
    }

    @Test
    public void rejectsUnknownDatasetWithCandidateList() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customerz", "region"));
        req.getValues().add(metricRef("net_revenue", null));
        try {
            validator.validate(req, schema);
            fail("expected validation error");
        } catch (OssieAiValidationException e) {
            assertEquals("rows[0].dataset", e.getField());
            assertEquals(List.of("customers"), e.getAvailable());
        }
    }

    @Test
    public void rejectsUnknownFieldWithCandidateList() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "regions"));
        req.getValues().add(metricRef("net_revenue", null));
        try {
            validator.validate(req, schema);
            fail("expected validation error");
        } catch (OssieAiValidationException e) {
            assertEquals("rows[0].field", e.getField());
            assertTrue(e.getAvailable().contains("region"));
            assertTrue(e.getAvailable().contains("state"));
        }
    }

    @Test
    public void rejectsUnsupportedAggregationOverride() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        // line_count is COUNT(*) — only COUNT override is allowed. Ask for SUM → rejected.
        req.getValues().add(metricRef("line_count", "SUM"));
        try {
            validator.validate(req, schema);
            fail("expected validation error");
        } catch (OssieAiValidationException e) {
            assertEquals("values[0].aggregation", e.getField());
            assertEquals(List.of("COUNT"), e.getAvailable());
        }
    }

    @Test
    public void rejectsFilterWithMissingBetweenValues() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        req.getValues().add(metricRef("net_revenue", null));
        OssieAiQueryRequest.FilterExpr f = new OssieAiQueryRequest.FilterExpr();
        f.setDataset("customers");
        f.setField("region");
        f.setOp("BETWEEN");
        f.setValues(List.of("only-one"));
        req.getFilters().add(f);
        try {
            validator.validate(req, schema);
            fail("expected validation error");
        } catch (OssieAiValidationException e) {
            assertEquals("filters[0].values", e.getField());
        }
    }

    @Test
    public void rejectsSortWithBothMetricAndField() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        req.getValues().add(metricRef("net_revenue", null));
        OssieAiQueryRequest.SortRef s = new OssieAiQueryRequest.SortRef();
        s.setMetric("net_revenue");
        s.setField("region");
        s.setDataset("customers");
        req.getSorts().add(s);
        try {
            validator.validate(req, schema);
            fail("expected validation error");
        } catch (OssieAiValidationException e) {
            assertEquals("sorts[0]", e.getField());
        }
    }

    @Test
    public void allowsEmptyInFilter() {
        // Empty IN is a legit shape — translator synthesises 1=0 → zero rows.
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        req.getValues().add(metricRef("net_revenue", null));
        OssieAiQueryRequest.FilterExpr f = new OssieAiQueryRequest.FilterExpr();
        f.setDataset("customers");
        f.setField("region");
        f.setOp("IN");
        req.getFilters().add(f);
        validator.validate(req, schema);
    }

    // ---- timeAxis validation (#1399) ----

    @Test
    public void validateTimeAxisRejectsMissingDot() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        try {
            validator.validateTimeAxis("region", req, schema);
            fail("expected validation error on malformed timeAxis");
        } catch (OssieAiValidationException e) {
            assertEquals("timeAxis", e.getField());
        }
    }

    @Test
    public void validateTimeAxisRejectsUnknownDataset() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        try {
            validator.validateTimeAxis("orders.month", req, schema);
            fail();
        } catch (OssieAiValidationException e) {
            assertEquals("timeAxis", e.getField());
            assertTrue(e.getMessage().contains("unknown dataset"));
        }
    }

    @Test
    public void validateTimeAxisRejectsAxisNotInQuery() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        // 'state' exists on the schema but isn't in rows[] or columns[].
        try {
            validator.validateTimeAxis("customers.state", req, schema);
            fail();
        } catch (OssieAiValidationException e) {
            assertEquals("timeAxis", e.getField());
            assertTrue(e.getMessage().contains("not in rows[]"));
            assertEquals(List.of("customers.region"), e.getAvailable());
        }
    }

    @Test
    public void validateTimeAxisAcceptsAxisFromRows() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        validator.validateTimeAxis("customers.region", req, schema);
    }

    @Test
    public void validateTimeAxisAcceptsAxisFromColumns() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getColumns().add(fieldRef("customers", "region"));
        validator.validateTimeAxis("customers.region", req, schema);
    }

    // ---- synonym resolution (#1408) ----

    @Test
    public void metricSynonymRewrittenToCanonical() {
        // Declare "revenue" and "turnover" as synonyms for net_revenue via the alias map.
        schema.getMetricAliases().put("revenue", "net_revenue");
        schema.getMetricAliases().put("turnover", "net_revenue");

        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        req.getRows().add(fieldRef("customers", "region"));
        OssieAiQueryRequest.MetricRef mref = metricRef("revenue", null);
        req.getValues().add(mref);

        validator.validate(req, schema);

        // Validator MUST rewrite the request to canonical so the executor sees a real name.
        assertEquals("net_revenue", mref.getMetric());
    }

    @Test
    public void datasetSynonymRewrittenToCanonical() {
        schema.getDatasetAliases().put("clients", "customers");

        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        OssieAiQueryRequest.FieldRef fref = fieldRef("clients", "region");
        req.getRows().add(fref);
        req.getValues().add(metricRef("net_revenue", null));

        validator.validate(req, schema);
        assertEquals("customers", fref.getDataset());
    }

    @Test
    public void fieldSynonymRewrittenWhenDatasetMatches() {
        // "state" already exists on customers. Add a synonym "geo" → customers.region.
        schema.getFieldAliases().put("geo", "customers.region");

        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        OssieAiQueryRequest.FieldRef fref = fieldRef("customers", "geo");
        req.getRows().add(fref);
        req.getValues().add(metricRef("net_revenue", null));

        validator.validate(req, schema);
        assertEquals("region", fref.getField());
    }

    @Test
    public void fieldSynonymIgnoredWhenDatasetDoesNotMatch() {
        // "geo" is a synonym for stores.state — but the caller asked for customers.geo.
        // Cross-dataset synonym should NOT silently rewrite (ambiguous).
        OssieAiSchema.Dataset stores = new OssieAiSchema.Dataset();
        stores.setName("stores");
        OssieAiSchema.Field storesState = new OssieAiSchema.Field();
        storesState.setName("state");
        stores.getFields().put("state", storesState);
        schema.getDatasets().put("stores", stores);
        schema.getFieldAliases().put("geo", "stores.state");

        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel("Pharma");
        // Look up "geo" against `customers` which has no such field. Should reject.
        req.getRows().add(fieldRef("customers", "geo"));
        req.getValues().add(metricRef("net_revenue", null));
        try {
            validator.validate(req, schema);
            fail("expected validation error — cross-dataset synonym should not rewrite");
        } catch (OssieAiValidationException e) {
            assertEquals("rows[0].field", e.getField());
            assertTrue(e.getMessage().contains("unknown field 'geo'"));
        }
    }

    private static OssieAiQueryRequest.FieldRef fieldRef(String dataset, String field) {
        OssieAiQueryRequest.FieldRef f = new OssieAiQueryRequest.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        return f;
    }

    private static OssieAiQueryRequest.MetricRef metricRef(String metric, String agg) {
        OssieAiQueryRequest.MetricRef m = new OssieAiQueryRequest.MetricRef();
        m.setMetric(metric);
        m.setAggregation(agg);
        return m;
    }
}
