/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.DialectExpression;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.List;
import org.junit.Test;

/** saiku#1730 — the validation report that gates the save step. */
public class OssieYamlValidatorTest {

    private final OssieYamlValidator validator = new OssieYamlValidator();

    private static Expression expr(String sql) {
        DialectExpression d = new DialectExpression();
        d.setDialect("ANSI_SQL");
        d.setExpression(sql);
        Expression e = new Expression();
        e.getDialects().add(d);
        return e;
    }

    private static Field field(String name) {
        Field f = new Field();
        f.setName(name);
        f.setExpression(expr(name));
        return f;
    }

    private static Dataset dataset(String name, String source, Field... fields) {
        Dataset ds = new Dataset();
        ds.setName(name);
        ds.setSource(source);
        ds.setFields(List.of(fields));
        return ds;
    }

    private SemanticModel model(List<Dataset> datasets, List<Metric> metrics, List<Relationship> rels) {
        SemanticModel m = new SemanticModel();
        m.setName("test");
        m.setDatasets(datasets);
        m.setMetrics(metrics);
        m.setRelationships(rels);
        OssieDocument doc = new OssieDocument();
        doc.getSemanticModel().add(m);
        return m;
    }

    private OssieValidationReport validate(SemanticModel m) {
        OssieDocument doc = new OssieDocument();
        doc.getSemanticModel().add(m);
        return validator.validate(doc);
    }

    private static List<String> codes(OssieValidationReport r) {
        return r.getDiagnostics().stream().map(ConversionDiagnostic::getCode).toList();
    }

    @Test
    public void cleanModelHasNoErrors() {
        SemanticModel m =
                model(List.of(dataset("orders", "orders", field("id"), field("customer_id"))), List.of(), List.of());
        OssieValidationReport report = validate(m);
        assertFalse(report.getDiagnostics().toString(), report.hasErrors());
        assertEquals(1, report.getDatasetCount());
        assertEquals(2, report.getFieldCount());
    }

    @Test
    public void relationshipToUnknownDatasetIsAnError() {
        Relationship rel = new Relationship();
        rel.setName("orders_to_users");
        rel.setFrom("orders");
        rel.setTo("users");
        rel.getFromColumns().add("customer_id");
        rel.getToColumns().add("id");
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "orders", field("id"))), List.of(), List.of(rel)));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("RELATIONSHIP_UNKNOWN_DATASET"));
    }

    @Test
    public void relationshipToUnknownColumnIsAWarning() {
        Relationship rel = new Relationship();
        rel.setName("orders_to_orders");
        rel.setFrom("orders");
        rel.setTo("orders");
        rel.getFromColumns().add("nope");
        rel.getToColumns().add("id");
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "orders", field("id"))), List.of(), List.of(rel)));
        assertFalse(report.hasErrors());
        assertTrue(codes(report).contains("RELATIONSHIP_UNKNOWN_COLUMN"));
    }

    @Test
    public void relationshipWithMismatchedColumnCountsIsAnError() {
        Relationship rel = new Relationship();
        rel.setName("orders_to_orders");
        rel.setFrom("orders");
        rel.setTo("orders");
        rel.getFromColumns().add("id");
        rel.getFromColumns().add("customer_id");
        rel.getToColumns().add("id");
        OssieValidationReport report = validate(model(
                List.of(dataset("orders", "orders", field("id"), field("customer_id"))), List.of(), List.of(rel)));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("RELATIONSHIP_COLUMN_ARITY"));
    }

    @Test
    public void metricReferencingUnknownDatasetIsAnError() {
        Metric revenue = new Metric();
        revenue.setName("revenue");
        revenue.setExpression(expr("SUM(\"ghosts\".\"amount\")"));
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "orders", field("amount"))), List.of(revenue), List.of()));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("METRIC_UNKNOWN_DATASET"));
    }

    @Test
    public void metricReferencingUnknownFieldIsAWarning() {
        Metric revenue = new Metric();
        revenue.setName("revenue");
        revenue.setExpression(expr("SUM(\"orders\".\"nope\")"));
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "orders", field("amount"))), List.of(revenue), List.of()));
        assertFalse(report.hasErrors());
        assertTrue(codes(report).contains("METRIC_UNKNOWN_FIELD"));
    }

    @Test
    public void duplicateDatasetIsAnError() {
        OssieValidationReport report = validate(model(
                List.of(dataset("orders", "orders", field("id")), dataset("orders", "orders_2", field("id"))),
                List.of(),
                List.of()));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("DUPLICATE_DATASET"));
    }

    @Test
    public void duplicateMetricIsAnError() {
        Metric a = new Metric();
        a.setName("revenue");
        a.setExpression(expr("SUM(\"orders\".\"amount\")"));
        Metric b = new Metric();
        b.setName("revenue");
        b.setExpression(expr("SUM(\"orders\".\"amount\")"));
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "orders", field("amount"))), List.of(a, b), List.of()));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("DUPLICATE_METRIC"));
    }

    @Test
    public void missingSourceAndFieldsAreWarnings() {
        OssieValidationReport report = validate(model(List.of(dataset("orders", null)), List.of(), List.of()));
        assertFalse(report.hasErrors());
        assertTrue(codes(report).contains("DATASET_NO_SOURCE"));
        assertTrue(codes(report).contains("NO_FIELDS"));
    }

    @Test
    public void leftoverLiquidIsAWarning() {
        Field f = field("id");
        f.setExpression(expr("{{ ref('orders') }}.id"));
        OssieValidationReport report = validate(model(List.of(dataset("orders", "orders", f)), List.of(), List.of()));
        assertFalse(report.hasErrors());
        assertTrue(codes(report).contains("LIQUID_RESIDUE"));
    }

    @Test
    public void modelWithoutDatasetsIsAnError() {
        OssieValidationReport report = validate(model(List.of(), List.of(), List.of()));
        assertTrue(report.hasErrors());
        assertTrue(codes(report).contains("NO_DATASETS"));
    }

    @Test
    public void countsAndSummariesAreReported() {
        Relationship rel = new Relationship();
        rel.setName("orders_to_orders");
        rel.setFrom("orders");
        rel.setTo("orders");
        rel.getFromColumns().add("id");
        rel.getToColumns().add("id");
        Metric m = new Metric();
        m.setName("count");
        m.setExpression(expr("COUNT(*)"));
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", "public.orders", field("id"))), List.of(m), List.of(rel)));
        assertEquals(1, report.getDatasetCount());
        assertEquals(1, report.getFieldCount());
        assertEquals(1, report.getMetricCount());
        assertEquals(1, report.getRelationshipCount());
        assertEquals(1, report.getDatasets().size());
        assertEquals("public.orders", report.getDatasets().get(0).getSource());
    }

    @Test
    public void errorsSortFirst() {
        Relationship rel = new Relationship();
        rel.setName("orders_to_ghosts");
        rel.setFrom("orders");
        rel.setTo("ghosts");
        rel.getFromColumns().add("id");
        rel.getToColumns().add("id");
        OssieValidationReport report =
                validate(model(List.of(dataset("orders", null, field("id"))), List.of(), List.of(rel)));
        assertTrue(report.getDiagnostics().size() > 1);
        assertEquals(
                ConversionDiagnostic.Severity.ERROR,
                report.getDiagnostics().get(0).getSeverity());
    }
}
