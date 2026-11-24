/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuValidationException;

/**
 * Shelf-state → SQL translation. String-compare assertions on the emitted SQL — the actual query
 * behaviour is covered separately by the end-to-end IT against a live H2 fixture.
 */
public class OssieShelfSqlTranslatorTest {

    private OssieShelfSqlTranslator translator;
    private OssieModelDto semantic;

    @Before
    public void setUp() {
        translator = new OssieShelfSqlTranslator();
        semantic = new OssieModelDto();
        semantic.setName("SALES");

        // The datasets/fields the shelf states below reference. saiku#2526: every shelf
        // identifier is resolved against the model before SQL is emitted, so these tests declare
        // them. No ANSI expression is declared — the fields fall back to their own name, which
        // is exactly what the tests asserted before the resolution gate existed.
        OssieModelDto.Dataset customers = new OssieModelDto.Dataset();
        customers.setName("customers");
        customers.getFields().add(field("region"));
        customers.getFields().add(field("name"));
        OssieModelDto.Dataset dates = new OssieModelDto.Dataset();
        dates.setName("dates");
        dates.getFields().add(field("year"));
        OssieModelDto.Dataset orders = new OssieModelDto.Dataset();
        orders.setName("orders");
        orders.getFields().add(field("amount"));
        semantic.getDatasets().add(customers);
        semantic.getDatasets().add(dates);
        semantic.getDatasets().add(orders);

        OssieModelDto.Metric revenue = new OssieModelDto.Metric();
        revenue.setName("revenue");
        revenue.setExpression("SUM(\"orders\".\"amount\")");
        semantic.getMetrics().add(revenue);

        OssieModelDto.Metric orderCount = new OssieModelDto.Metric();
        orderCount.setName("order_count");
        orderCount.setExpression("COUNT(*)");
        semantic.getMetrics().add(orderCount);
    }

    @Test
    public void simpleGroupBy() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\""
                        + " FROM \"orders\", \"customers\""
                        + " GROUP BY \"customers\".\"region\"",
                translator.translate(m, semantic));
    }

    @Test
    public void multiFieldColumnsAndValues() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setColumns(List.of(fieldRef("dates", "year")));
        m.setValues(List.of(metric("revenue"), metric("order_count")));

        String sql = translator.translate(m, semantic);
        // Assert on shape: exactly one comma-separated FROM referencing three datasets, plus
        // SELECT lists both metrics.
        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " \"dates\".\"year\" AS \"dates.year\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\","
                        + " COUNT(*) AS \"order_count\""
                        + " FROM \"orders\", \"customers\", \"dates\""
                        + " GROUP BY \"customers\".\"region\", \"dates\".\"year\"",
                sql);
    }

    @Test
    public void filterConjunction() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        OssieQueryModel.FilterExpr eq = new OssieQueryModel.FilterExpr();
        eq.setDataset("customers");
        eq.setField("region");
        eq.setOp("EQ");
        eq.setValue("NA");

        OssieQueryModel.FilterExpr in = new OssieQueryModel.FilterExpr();
        in.setDataset("dates");
        in.setField("year");
        in.setOp("IN");
        in.setValues(List.of("2024", "2025"));

        m.setFilters(List.of(eq, in));

        String sql = translator.translate(m, semantic);
        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\""
                        + " FROM \"orders\", \"customers\", \"dates\""
                        + " WHERE \"customers\".\"region\" = 'NA' AND \"dates\".\"year\" IN (2024, 2025)"
                        + " GROUP BY \"customers\".\"region\"",
                sql);
    }

    @Test
    public void betweenFilterAndSort() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        OssieQueryModel.FilterExpr between = new OssieQueryModel.FilterExpr();
        between.setDataset("orders");
        between.setField("amount");
        between.setOp("BETWEEN");
        between.setValues(List.of("100", "500"));
        m.setFilters(List.of(between));

        OssieQueryModel.SortRef sort = new OssieQueryModel.SortRef();
        sort.setMetric("revenue");
        sort.setDirection("DESC");
        m.setSorts(List.of(sort));
        m.setLimit(10);

        String sql = translator.translate(m, semantic);
        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\""
                        + " FROM \"orders\", \"customers\""
                        + " WHERE \"orders\".\"amount\" BETWEEN 100 AND 500"
                        + " GROUP BY \"customers\".\"region\""
                        + " ORDER BY \"revenue\" DESC"
                        + " LIMIT 10",
                sql);
    }

    @Test
    public void emptyInSynthesizesFalse() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr in = new OssieQueryModel.FilterExpr();
        in.setDataset("customers");
        in.setField("region");
        in.setOp("IN");
        m.setFilters(List.of(in));

        String sql = translator.translate(m, semantic);
        // Trivially-false predicate so an empty IN produces zero rows without a parse error.
        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\""
                        + " FROM \"orders\", \"customers\""
                        + " WHERE 1 = 0"
                        + " GROUP BY \"customers\".\"region\"",
                sql);
    }

    @Test
    public void quoteEscape() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr eq = new OssieQueryModel.FilterExpr();
        eq.setDataset("customers");
        eq.setField("name");
        eq.setOp("EQ");
        eq.setValue("O'Brien");
        m.setFilters(List.of(eq));

        String sql = translator.translate(m, semantic);
        // Single-quote inside literal escapes to '' per SQL standard.
        assertEquals(
                "SELECT \"customers\".\"region\" AS \"customers.region\","
                        + " SUM(\"orders\".\"amount\") AS \"revenue\""
                        + " FROM \"orders\", \"customers\""
                        + " WHERE \"customers\".\"name\" = 'O''Brien'"
                        + " GROUP BY \"customers\".\"region\"",
                sql);
    }

    @Test
    public void missingFactDatasetRejected() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setValues(List.of(metric("revenue")));
        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("fact dataset"));
        }
    }

    @Test(expected = SaikuValidationException.class)
    public void unknownMetricRejected() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setValues(List.of(metric("does_not_exist")));
        translator.translate(m, semantic);
    }

    @Test
    public void swapAggregationRewritesSumToAvg() {
        assertEquals(
                "AVG(\"orders\".\"amount\")",
                OssieShelfSqlTranslator.swapAggregation("SUM(\"orders\".\"amount\")", "AVG"));
    }

    @Test
    public void swapAggregationLeavesUnknownOverrideAlone() {
        assertEquals(
                "SUM(\"orders\".\"amount\")",
                OssieShelfSqlTranslator.swapAggregation("SUM(\"orders\".\"amount\")", "MEDIAN"));
    }

    @Test
    public void swapAggregationDoesNotWreckCompoundExpressions() {
        // Two-clause expression → outer isn't a single aggregate call. Regex would
        // greedily match, so the depth check bails.
        assertEquals("SUM(x) + AVG(y)", OssieShelfSqlTranslator.swapAggregation("SUM(x) + AVG(y)", "MAX"));
    }

    @Test
    public void swapAggregationRefusesStarArgWhenOverrideIsntCount() {
        // Regression for OssieFuzzIT combinatorial fuzz 2026-07-08: overriding COUNT(*)
        // with SUM / AVG / MIN / MAX emits invalid SQL. The translator now keeps the
        // declared aggregation for the star-arg edge case.
        assertEquals("COUNT(*)", OssieShelfSqlTranslator.swapAggregation("COUNT(*)", "SUM"));
        assertEquals("COUNT(*)", OssieShelfSqlTranslator.swapAggregation("COUNT(*)", "AVG"));
        assertEquals("COUNT(*)", OssieShelfSqlTranslator.swapAggregation("COUNT(*)", "MIN"));
        assertEquals("COUNT(*)", OssieShelfSqlTranslator.swapAggregation("COUNT(*)", "MAX"));
        // COUNT → COUNT is a no-op-shaped rewrite but valid.
        assertEquals("COUNT(*)", OssieShelfSqlTranslator.swapAggregation("COUNT(*)", "COUNT"));
    }

    @Test
    public void aggregationOverrideAppearsInEmittedSql() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        OssieQueryModel.MetricRef mr = metric("revenue");
        mr.setAggregation("AVG");
        m.setValues(java.util.List.of(mr));
        String sql = translator.translate(m, semantic);
        // The 'revenue' metric was declared as SUM("orders"."amount"); override rewrites
        // the outer SUM to AVG in the emitted SQL.
        assertTrue("expected AVG in emitted SQL, got: " + sql, sql.contains("AVG(\"orders\".\"amount\")"));
        assertTrue("original SUM should be gone", !sql.contains("SUM(\"orders\".\"amount\")"));
    }

    // ---- saiku#1393: role-based row predicates + HIDE enforcement ----

    private static void addRowPredicate(OssieModelDto semantic, String dataset, String role, String expression) {
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            if (ds.getName().equals(dataset)) {
                ds.getRowPredicates().add(new OssieModelDto.RowPredicate(role, expression));
                return;
            }
        }
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName(dataset);
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate(role, expression));
        semantic.getDatasets().add(ds);
    }

    @Test
    public void rowPredicateInjectedForMatchingRole() {
        addRowPredicate(semantic, "customers", "ROLE_APAC", "REGION IN ('APAC', 'Japan')");

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        String sql = translator.translate(m, semantic, Set.of("ROLE_APAC"));
        assertTrue(sql, sql.contains("WHERE REGION IN ('APAC', 'Japan')"));
    }

    @Test
    public void rowPredicateNotInjectedWhenCallerLacksRole() {
        addRowPredicate(semantic, "customers", "ROLE_APAC", "REGION IN ('APAC', 'Japan')");

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        String sql = translator.translate(m, semantic, Set.of("ROLE_OTHER"));
        assertTrue(sql, !sql.contains("WHERE"));
    }

    @Test
    public void rowPredicatesFromTwoHeldRolesAreOred() {
        addRowPredicate(semantic, "customers", "ROLE_APAC", "REGION = 'APAC'");
        addRowPredicate(semantic, "customers", "ROLE_EMEA", "REGION = 'EMEA'");

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        String sql = translator.translate(m, semantic, Set.of("ROLE_APAC", "ROLE_EMEA"));
        assertTrue(sql, sql.contains("WHERE (REGION = 'APAC' OR REGION = 'EMEA')"));
    }

    @Test
    public void rowPredicateAndsWithShelfFilter() {
        addRowPredicate(semantic, "customers", "ROLE_APAC", "REGION = 'APAC'");

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr eq = new OssieQueryModel.FilterExpr();
        eq.setDataset("customers");
        eq.setField("region");
        eq.setOp("NEQ");
        eq.setValue("TEST");
        m.setFilters(List.of(eq));

        String sql = translator.translate(m, semantic, Set.of("ROLE_APAC"));
        assertTrue(sql, sql.contains("WHERE \"customers\".\"region\" <> 'TEST' AND REGION = 'APAC'"));
    }

    @Test
    public void hiddenFieldDeniedForNonMatchingRole() {
        dataset("customers").getFields().add(restrictedField("ssn", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "ssn")));
        m.setValues(List.of(metric("revenue")));

        try {
            translator.translate(m, semantic, Set.of("ROLE_ANALYST"));
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("customers.ssn"));
        }
    }

    @Test
    public void hiddenFieldPermittedForMatchingRole() {
        dataset("customers").getFields().add(restrictedField("ssn", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "ssn")));
        m.setValues(List.of(metric("revenue")));

        // Doesn't throw — the field has no ANSI expression declared so it falls back to its own
        // name, same as any other unresolved-expression field.
        String sql = translator.translate(m, semantic, Set.of("ROLE_ADMIN"));
        assertTrue(sql, sql.contains("\"customers\".\"ssn\""));
    }

    @Test
    public void deniedMetricRejectedRegardlessOfRowPredicates() {
        OssieModelDto.Metric exec = new OssieModelDto.Metric();
        exec.setName("exec_only");
        exec.setExpression("SUM(\"orders\".\"amount\")");
        exec.setAllowRoles(List.of("ROLE_EXEC"));
        semantic.getMetrics().add(exec);

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setValues(List.of(metric("exec_only")));

        try {
            translator.translate(m, semantic, Set.of("ROLE_ANALYST"));
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exec_only"));
        }
    }

    /** saiku#2526 — a field restricted to a single role, for the HIDE tests. */
    private static OssieModelDto.Field restrictedField(String name, String allowedRole) {
        OssieModelDto.Field f = new OssieModelDto.Field();
        f.setName(name);
        f.setAllowRoles(List.of(allowedRole));
        return f;
    }

    private static OssieModelDto.Field field(String name) {
        OssieModelDto.Field f = new OssieModelDto.Field();
        f.setName(name);
        return f;
    }

    private OssieModelDto.Dataset dataset(String name) {
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            if (ds.getName().equals(name)) return ds;
        }
        throw new IllegalStateException("test dataset not declared: " + name);
    }

    private static OssieQueryModel.FieldRef fieldRef(String dataset, String field) {
        OssieQueryModel.FieldRef f = new OssieQueryModel.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        return f;
    }

    private static OssieQueryModel.MetricRef metric(String name) {
        OssieQueryModel.MetricRef m = new OssieQueryModel.MetricRef();
        m.setMetric(name);
        return m;
    }

    // ---- saiku#2319: backslash literals on backslash-escaping warehouses ----

    private OssieQueryModel modelFilteringRegion(String op, String value, List<String> values) {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setDataset("customers");
        f.setField("region");
        f.setOp(op);
        f.setValue(value);
        if (values != null) f.setValues(values);
        m.setFilters(List.of(f));
        return m;
    }

    @Test
    public void backslashFilterValueIsRejectedOnMysql() {
        for (String url : new String[] {"jdbc:mysql://db/sales", "jdbc:mariadb://db/sales", null, "jdbc:weird:x"}) {
            try {
                translator.translate(modelFilteringRegion("EQ", "\\' OR 1=1 -- ", null), semantic, Set.of(), url);
                org.junit.Assert.fail("expected backslash rejection for " + url);
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().toLowerCase().contains("backslash"));
            }
        }
    }

    @Test
    public void backslashInInAndBetweenValuesIsRejectedOnMysql() {
        String mysql = "jdbc:mysql://db/sales";
        try {
            translator.translate(modelFilteringRegion("IN", null, List.of("ok", "a\\b")), semantic, Set.of(), mysql);
            org.junit.Assert.fail("IN value with backslash must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            translator.translate(modelFilteringRegion("BETWEEN", null, List.of("a", "b\\")), semantic, Set.of(), mysql);
            org.junit.Assert.fail("BETWEEN value with backslash must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void backslashFilterValueStillWorksOnStandardConformingWarehouses() {
        String sql = translator.translate(
                modelFilteringRegion("EQ", "C:\\data", null), semantic, Set.of(), "jdbc:postgresql://db/sales");
        assertTrue(sql, sql.contains("= 'C:\\data'"));
    }

    @Test
    public void valuesWithoutBackslashAreUnaffectedOnMysql() {
        String sql = translator.translate(
                modelFilteringRegion("EQ", "O'Neil", null), semantic, Set.of(), "jdbc:mysql://db/sales");
        assertTrue(sql, sql.contains("= 'O''Neil'"));
    }

    // ---- saiku#2526: physical-column bypass via unresolvable identifiers ----

    @Test
    public void physicalColumnNameInRowsIsRejected() {
        // `email` is the semantic field; EMAIL_ADDRESS is its physical column. The old
        // fall-through emitted the unresolvable name as a quoted column, reading the restricted
        // values of the caller's role-filtered model.
        OssieModelDto.Dataset customers = dataset("customers");
        customers.getFields().add(restrictedField("email", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "EMAIL_ADDRESS")));
        m.setValues(List.of(metric("revenue")));

        try {
            translator.translate(m, semantic, Set.of("ROLE_USER"));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    @Test
    public void physicalColumnNameInFilterWithoutDatasetIsRejected() {
        OssieModelDto.Dataset customers = dataset("customers");
        customers.getFields().add(restrictedField("email", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("orders", "amount")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setField("EMAIL_ADDRESS"); // no dataset — previously skipped entirely
        f.setOp("EQ");
        f.setValue("alice@example.com");
        m.setFilters(List.of(f));

        try {
            translator.translate(m, semantic, Set.of("ROLE_USER"));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    @Test
    public void deniedFieldInFilterWithoutDatasetIsDenied() {
        OssieModelDto.Dataset customers = dataset("customers");
        customers.getFields().add(restrictedField("email", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("orders", "amount")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setField("email"); // resolves — but every dataset declaring it denies the caller
        f.setOp("EQ");
        f.setValue("alice@example.com");
        m.setFilters(List.of(f));

        try {
            translator.translate(m, semantic, Set.of("ROLE_USER"));
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("email"));
        }
    }

    @Test
    public void physicalColumnNameInSortIsRejected() {
        OssieModelDto.Dataset customers = dataset("customers");
        customers.getFields().add(restrictedField("email", "ROLE_ADMIN"));

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setDataset("customers");
        s.setField("EMAIL_ADDRESS");
        s.setDirection("ASC");
        m.setSorts(List.of(s));

        try {
            translator.translate(m, semantic, Set.of("ROLE_USER"));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    @Test
    public void unknownMetricInSortIsRejected() {
        // A sort's metric was never checked at all — it used to be emitted as a bare quoted
        // column regardless of the model.
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));
        OssieQueryModel.SortRef s = new OssieQueryModel.SortRef();
        s.setMetric("EMAIL_ADDRESS");
        s.setDirection("DESC");
        m.setSorts(List.of(s));

        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("EMAIL_ADDRESS"));
        }
    }

    @Test
    public void unknownFieldForEveryCallerIsRejectedEvenWithoutRoles() {
        // The resolution gate is not role-scoped: an identifier that is no field of the model is
        // rejected for every caller, on the unscoped translate() path too.
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "not_a_field")));
        m.setValues(List.of(metric("revenue")));

        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not_a_field"));
        }
    }

    @Test
    public void validFieldsStillTranslateAfterTheResolutionGate() {
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        m.setValues(List.of(metric("revenue")));

        String sql = translator.translate(m, semantic);
        assertTrue(sql, sql.contains("SELECT \"customers\".\"region\" AS \"customers.region\""));
    }

    @Test
    public void unqualifiedFilterUsesDeclaredDatasetAndExpressionDespiteHiddenPhysicalCollision() {
        OssieModelDto.Field hidden = restrictedField("hidden_email", "ROLE_ADMIN");
        hidden.setExpression("email");
        dataset("customers").getFields().add(hidden);
        OssieModelDto.Field visible = field("email");
        visible.setExpression("ORDER_EMAIL");
        dataset("orders").getFields().add(visible);

        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("customers");
        m.setRows(List.of(fieldRef("customers", "region")));
        OssieQueryModel.FilterExpr filter = new OssieQueryModel.FilterExpr();
        filter.setField("email");
        filter.setOp("EQ");
        filter.setValue("alice@example.com");
        m.setFilters(List.of(filter));

        String sql = translator.translate(m, semantic, Set.of("ROLE_USER"));
        assertTrue(sql, sql.contains("FROM \"customers\", \"orders\""));
        assertTrue(sql, sql.contains("WHERE \"orders\".\"ORDER_EMAIL\" = 'alice@example.com'"));
        assertTrue(sql, !sql.contains("WHERE \"email\""));
    }

    @Test
    public void ambiguousUnqualifiedFilterIsAValidationError() {
        dataset("orders").getFields().add(field("region"));
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        OssieQueryModel.FilterExpr filter = new OssieQueryModel.FilterExpr();
        filter.setField("region");
        filter.setOp("EQ");
        filter.setValue("NA");
        m.setFilters(List.of(filter));

        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Ambiguous field 'region'"));
        }
    }

    @Test
    public void unqualifiedSortIsAValidationError() {
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("orders");
        m.setRows(List.of(fieldRef("customers", "region")));
        OssieQueryModel.SortRef sort = new OssieQueryModel.SortRef();
        sort.setField("region");
        m.setSorts(List.of(sort));

        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("requires a dataset"));
        }
    }

    @Test
    public void unknownFactDatasetIsAValidationError() {
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("not_a_dataset");
        m.setValues(List.of(metric("revenue")));

        try {
            translator.translate(m, semantic);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not_a_dataset"));
        }
    }
}
