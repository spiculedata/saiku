/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.query2.OssieQueryModel;

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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\""
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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\", \"SALES\".\"dates\""
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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\", \"SALES\".\"dates\""
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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\""
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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\""
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
                        + " FROM \"SALES\".\"orders\", \"SALES\".\"customers\""
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
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("OssieQueryModel.factDataset is required", e.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownMetricRejected() {
        OssieQueryModel m = new OssieQueryModel();
        m.setConnection("SALES");
        m.setModel("SALES");
        m.setFactDataset("orders");
        m.setValues(List.of(metric("does_not_exist")));
        translator.translate(m, semantic);
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
}
