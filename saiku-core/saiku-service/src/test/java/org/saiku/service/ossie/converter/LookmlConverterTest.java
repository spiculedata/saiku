/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * saiku#1730 — LookML → Ossie. The LookML blocks are the real dialect (comments, `${TABLE}`
 * references, {@code sql_table_name} sub-blocks, liquid) rather than a happy-path snippet, because
 * the degrading behaviour is what the issue's scope guard is about.
 */
public class LookmlConverterTest {

    private final LookmlConverter converter = new LookmlConverter();

    private SemanticModel convert(String lookml) throws Exception {
        VendorConversionResult r = converter.convert(VendorModelFile.single("orders.view", lookml), null);
        return r.getDocument().getSemanticModel().get(0);
    }

    private VendorConversionResult convertWith(String lookml) throws Exception {
        return converter.convert(VendorModelFile.single("orders.view", lookml), null);
    }

    @Test
    public void metadata() {
        assertEquals("lookml", converter.id());
        assertTrue(converter.fileExtensions().contains(".view"));
        assertNotNull(converter.description());
    }

    @Test
    public void viewBecomesDatasetWithFields() throws Exception {
        SemanticModel model = convert(
                """
                view: orders {
                  label: "Orders"
                  source_table: public.orders

                  dimension: id {
                    primary_key: yes
                    sql: ${TABLE}.id
                    hidden: no
                  }
                  dimension: status {
                    label: "Order status"
                    sql: ${TABLE}.status
                    description: "Lifecycle state of the order"
                  }
                  dimension: ordered_at {
                    sql: ${TABLE}.ordered_at
                    type: timestamp
                  }

                  measure: count {
                    type: count
                  }
                  measure: total_amount {
                    type: sum
                    sql: ${TABLE}.amount
                    label: "Total amount"
                  }
                }
                """);

        assertEquals(1, model.getDatasets().size());
        Dataset ds = model.getDatasets().get(0);
        assertEquals("orders", ds.getName());
        assertEquals("public.orders", ds.getSource());
        assertEquals("Orders", ds.getDescription());
        assertEquals(List.of("id"), ds.getPrimaryKey());
        assertEquals(3, ds.getFields().size());

        Field status = field(ds, "status");
        assertEquals("Order status", status.getLabel());
        assertEquals("Lifecycle state of the order", status.getDescription());
        // ${TABLE}. prefix is the view's own table — the field selects the bare column.
        assertEquals("status", primary(status));

        Field orderedAt = field(ds, "ordered_at");
        assertNotNull("timestamp dimension is flagged as a time dimension", orderedAt.getDimension());
        assertEquals(Boolean.TRUE, orderedAt.getDimension().getIsTime());

        // Ossie metrics are model-global, so measures are namespaced by view.
        assertEquals(2, model.getMetrics().size());
        assertEquals("COUNT(*)", expression(metric(model, "orders_count")));
        assertEquals("SUM(amount)", expression(metric(model, "orders_total_amount")));
        assertEquals("Total amount", metric(model, "orders_total_amount").getDescription());
    }

    @Test
    public void sqlTableNameBlockBecomesSource() throws Exception {
        SemanticModel model = convert(
                """
                view: users {
                  sql_table_name: {
                    schema: "public"
                    table: "users"
                  }
                  dimension: id {
                    sql: ${TABLE}.id
                  }
                }
                """);
        assertEquals("users", model.getDatasets().get(0).getSource());
    }

    @Test
    public void exploreJoinOnBecomesRelationship() throws Exception {
        VendorConversionResult result = converter.convert(
                VendorModelFile.single(
                        "orders.explore",
                        """
                        explore: orders {
                          from: orders
                          join: users {
                            from: users
                            type: left
                            relationship: many_to_one
                            join_on: ${orders.customer_id} = ${users.id} ;;
                          }
                        }
                        view: orders {
                          source_table: public.orders
                          dimension: id { sql: ${TABLE}.id }
                          dimension: customer_id { sql: ${TABLE}.customer_id }
                        }
                        view: users {
                          source_table: public.users
                          dimension: id { sql: ${TABLE}.id }
                        }
                        """),
                null);

        SemanticModel model = result.getDocument().getSemanticModel().get(0);
        assertEquals(2, model.getDatasets().size());
        assertEquals(1, model.getRelationships().size());
        Relationship rel = model.getRelationships().get(0);
        assertEquals("orders", rel.getFrom());
        assertEquals("users", rel.getTo());
        assertEquals(List.of("customer_id"), rel.getFromColumns());
        assertEquals(List.of("id"), rel.getToColumns());
    }

    @Test
    public void liquidDegradesToWarningNotFailure() throws Exception {
        VendorConversionResult result = convertWith(
                """
                view: orders {
                  source_table: public.orders
                  dimension: id {
                    sql: {{ var('x') }}id
                  }
                }
                """);
        // The import still produced a model...
        assertEquals(
                1, result.getDocument().getSemanticModel().get(0).getDatasets().size());
        // ...and said so.
        assertTrue(codes(result).contains("LIQUID_RESIDUE")
                || codes(result).contains("LIQUID_IN_MEASURE")
                || codes(result).contains("FIELD_NO_SQL"));
    }

    @Test
    public void hiddenAndUnsupportedElementsAreReported() throws Exception {
        VendorConversionResult result = convertWith(
                """
                view: orders {
                  source_table: public.orders
                  dimension: secret {
                    sql: ${TABLE}.secret
                    hidden: yes
                  }
                  dimension: id {
                    sql: ${TABLE}.id
                  }
                  measure: names {
                    type: string_agg
                    sql: ${TABLE}.name
                  }
                }
                """);
        SemanticModel model = result.getDocument().getSemanticModel().get(0);
        assertEquals(1, model.getDatasets().get(0).getFields().size());
        assertEquals(0, model.getMetrics().size());
        assertTrue(codes(result).contains("HIDDEN_FIELD"));
        assertTrue(codes(result).contains("UNSUPPORTED_MEASURE"));
    }

    @Test
    public void derivedTableWarnsAndFallsBackToTheViewName() throws Exception {
        VendorConversionResult result = convertWith(
                """
                view: orders_current {
                  derived_table: {
                    sql: select * from orders where status = 'current' ;;
                  }
                  dimension: id { sql: id }
                }
                """);
        Dataset ds =
                result.getDocument().getSemanticModel().get(0).getDatasets().get(0);
        assertEquals("orders_current", ds.getSource());
        assertTrue(codes(result).contains("DERIVED_TABLE"));
    }

    @Test
    public void sqlOnJoinIsSkippedWithWarning() throws Exception {
        VendorConversionResult result = converter.convert(
                VendorModelFile.single(
                        "orders.explore",
                        """
                        explore: orders {
                          from: orders
                          join: users {
                            from: users
                            sql_on: ${orders.customer_id} = ${users.id} and users.active
                          }
                        }
                        view: orders {
                          source_table: public.orders
                          dimension: id { sql: ${TABLE}.id }
                        }
                        """),
                null);
        assertEquals(
                0,
                result.getDocument()
                        .getSemanticModel()
                        .get(0)
                        .getRelationships()
                        .size());
        assertTrue(codes(result).contains("SQL_JOIN"));
    }

    @Test
    public void countDistinctAndSumDistinctWrapDistinct() throws Exception {
        SemanticModel model = convert(
                """
                view: orders {
                  source_table: public.orders
                  dimension: id { sql: ${TABLE}.id }
                  measure: customers { type: count_distinct sql: ${TABLE}.customer_id }
                  measure: unique_amounts { type: sum_distinct sql: ${TABLE}.amount }
                }
                """);
        assertEquals("COUNT(DISTINCT customer_id)", expression(metric(model, "orders_customers")));
        assertEquals("SUM(DISTINCT amount)", expression(metric(model, "orders_unique_amounts")));
    }

    @Test
    public void commentsAndBlankLinesAreIgnored() throws Exception {
        SemanticModel model = convert(
                """
                # a leading comment
                view: orders {   # trailing comment

                  source_table: public.orders  # where the data lives
                  dimension: id { sql: ${TABLE}.id }
                }
                """);
        assertEquals(1, model.getDatasets().get(0).getFields().size());
    }

    @Test
    public void viewWithNoSourceTableStillImportsWithWarning() throws Exception {
        VendorConversionResult result = convertWith(
                """
                view: orders {
                  dimension: id { sql: ${TABLE}.id }
                }
                """);
        assertEquals(
                "orders",
                result.getDocument()
                        .getSemanticModel()
                        .get(0)
                        .getDatasets()
                        .get(0)
                        .getSource());
        assertTrue(codes(result).contains("NO_SOURCE_TABLE"));
    }

    @Test
    public void payloadWithoutViewsIsRejected() {
        try {
            convert("connection: production {}\n");
            fail("expected a conversion error for a payload with no view blocks");
        } catch (Exception e) {
            assertTrue(e instanceof VendorModelConversionException);
            assertTrue(e.getMessage().contains("view"));
        }
    }

    @Test
    public void emptyPayloadIsRejected() {
        try {
            convert("   \n # only a comment\n");
            fail("expected a conversion error for an empty payload");
        } catch (Exception e) {
            assertTrue(e instanceof VendorModelConversionException);
        }
    }

    @Test
    public void multipleFilesMergeIntoOneModel() throws Exception {
        VendorConversionResult result = converter.convert(
                List.of(
                        new VendorModelFile(
                                "orders.view",
                                "view: orders {\n source_table: public.orders\n dimension: id { sql: ${TABLE}.id }\n}"),
                        new VendorModelFile(
                                "users.view",
                                "view: users {\n source_table: public.users\n dimension: id { sql: ${TABLE}.id }\n}")),
                null);
        assertEquals(
                2, result.getDocument().getSemanticModel().get(0).getDatasets().size());
    }

    @Test
    public void requestNameWinsOverDerivedName() throws Exception {
        VendorConversionResult result = converter.convert(
                VendorModelFile.single("orders.view", "view: orders { dimension: id { sql: id } }"), "Sales");
        assertEquals("Sales", result.getModelName());
        assertEquals("Sales", result.getDocument().getSemanticModel().get(0).getName());
    }

    // ------------------------------------------------------------------

    private static Field field(Dataset ds, String name) {
        return ds.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }

    private static Metric metric(SemanticModel model, String name) {
        return model.getMetrics().stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    private static String primary(Field f) {
        return f.getExpression().getDialects().get(0).getExpression();
    }

    private static String expression(Metric m) {
        return m.getExpression().getDialects().get(0).getExpression();
    }

    private static List<String> codes(VendorConversionResult result) {
        return result.getDiagnostics().stream()
                .map(ConversionDiagnostic::getCode)
                .toList();
    }

    @Test
    public void noLiquidSurvivesIntoTheGeneratedExpressions() throws Exception {
        VendorConversionResult result = convertWith(
                """
                view: orders {
                  source_table: public.orders
                  dimension: id { sql: ${TABLE}.id }
                  measure: total { type: sum sql: ${TABLE}.amount }
                }
                """);
        Optional<Dataset> ds = result.getDocument().getSemanticModel().get(0).getDatasets().stream()
                .findFirst();
        assertTrue(ds.isPresent());
        assertFalse(ds.get().getSource().contains("{{"));
    }
}
