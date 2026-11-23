/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.SemanticModel;
import java.util.List;
import org.junit.Test;

/**
 * saiku#1730 — dbt {@code manifest.json} → Ossie. The manifest shape is trimmed from a real
 * dbt project (models, a source, a {@code unique} test, a compiled metric) because the parts
 * worth testing are the ones that differ from LookML: JSON, column-level tests, templated
 * metric expressions.
 */
public class DbtManifestConverterTest {

    private final DbtManifestConverter converter = new DbtManifestConverter();

    private static final String MANIFEST =
            """
            {
              "metadata": {"project_name": "analytics", "dbt_schema_version": "https://schemas.getdbt.com/dbt/manifest/v12.json"},
              "nodes": {
                "model.analytics.orders": {
                  "resource_type": "model",
                  "name": "orders",
                  "alias": "orders",
                  "database": "prod",
                  "schema": "core",
                  "relation_name": "\\"prod\\".\\"core\\".\\"orders\\"",
                  "description": "One row per order",
                  "columns": {
                    "id": {"name": "id", "data_type": "integer", "description": "Order id"},
                    "customer_id": {"name": "customer_id", "data_type": "integer"},
                    "ordered_at": {"name": "ordered_at", "data_type": "timestamp"},
                    "amount": {"name": "amount", "data_type": "numeric"}
                  },
                  "child_map": {"unique_orders_id": ["test.analytics.unique_orders_id.abc"]}
                },
                "test.analytics.unique_orders_id.abc": {
                  "resource_type": "test",
                  "name": "unique_orders_id",
                  "test_metadata": {"name": "unique", "kwargs": {"column_name": "id", "model": "orders"}}
                },
                "test.analytics.orders_customer_id_fk.abc": {
                  "resource_type": "test",
                  "name": "orders_customer_id_fk",
                  "test_metadata": {"name": "relationships", "kwargs": {
                    "to": "ref('customers')", "field_name": "customer_id", "column_name": "id"}}
                }
              },
              "sources": [
                {
                  "name": "crm",
                  "tables": [
                    {"name": "contacts", "schema": "crm", "description": "CRM contacts",
                     "columns": {"id": {"name": "id", "data_type": "integer"}}}
                  ]
                }
              ],
              "metrics": {
                "metric.analytics.revenue": {
                  "name": "revenue",
                  "label": "Revenue",
                  "description": "Total order revenue",
                  "compiled_code": "sum(orders.amount)"
                },
                "metric.analytics.templated": {
                  "name": "templated",
                  "expression": {"sql": "sum({{ ref('orders') }}.amount)"}
                },
                "metric.analytics.off": {"name": "off", "disabled": true, "compiled_code": "count(*)"}
              }
            }
            """;

    private SemanticModel convert() throws Exception {
        return converter
                .convert(VendorModelFile.single("manifest.json", MANIFEST), null)
                .getDocument()
                .getSemanticModel()
                .get(0);
    }

    @Test
    public void metadata() {
        assertEquals("dbt", converter.id());
        assertTrue(converter.fileExtensions().contains(".json"));
    }

    @Test
    public void modelBecomesDataset() throws Exception {
        Dataset orders = convert().getDatasets().get(0);
        assertEquals("orders", orders.getName());
        assertEquals("\"prod\".\"core\".\"orders\"", orders.getSource());
        assertEquals("One row per order", orders.getDescription());
        assertEquals(4, orders.getFields().size());
        assertEquals("id", orders.getFields().get(0).getName());
        assertEquals("Order id", orders.getFields().get(0).getDescription());
    }

    @Test
    public void timestampColumnBecomesTimeDimension() throws Exception {
        Dataset orders = convert().getDatasets().get(0);
        assertEquals(
                Boolean.TRUE,
                orders.getFields().stream()
                        .filter(f -> "ordered_at".equals(f.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getDimension()
                        .getIsTime());
        assertEquals(
                null,
                orders.getFields().stream()
                        .filter(f -> "amount".equals(f.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getDimension());
    }

    @Test
    public void uniqueTestBecomesPrimaryKey() throws Exception {
        assertEquals(List.of("id"), convert().getDatasets().get(0).getPrimaryKey());
    }

    @Test
    public void sourcesImportAsPrefixedDatasets() throws Exception {
        Dataset contacts = convert().getDatasets().stream()
                .filter(d -> "crm_contacts".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("crm_contacts", contacts.getName());
        assertEquals("contacts", contacts.getSource());
        assertEquals(1, contacts.getFields().size());
    }

    @Test
    public void compiledMetricImportsAndTemplatedOneIsReported() throws Exception {
        VendorConversionResult result = converter.convert(VendorModelFile.single("manifest.json", MANIFEST), null);
        SemanticModel model = result.getDocument().getSemanticModel().get(0);
        assertEquals(1, model.getMetrics().size());
        assertEquals("revenue", model.getMetrics().get(0).getName());
        assertEquals(
                "sum(orders.amount)",
                model.getMetrics().get(0).getExpression().getDialects().get(0).getExpression());
        List<String> codes = result.getDiagnostics().stream()
                .map(ConversionDiagnostic::getCode)
                .toList();
        assertTrue(codes.contains("TEMPLATED_METRIC"));
        assertTrue(codes.contains("METRIC_DISABLED"));
        assertTrue(codes.contains("RELATIONSHIP_TEST_SKIPPED"));
    }

    @Test
    public void modelNameComesFromProjectMetadata() throws Exception {
        assertEquals(
                "analytics",
                converter
                        .convert(VendorModelFile.single("manifest.json", MANIFEST), null)
                        .getModelName());
    }

    @Test
    public void requestNameWins() throws Exception {
        assertEquals(
                "Sales",
                converter
                        .convert(VendorModelFile.single("manifest.json", MANIFEST), "Sales")
                        .getModelName());
    }

    @Test
    public void nonJsonPayloadIsRejected() {
        try {
            converter.convert(VendorModelFile.single("manifest.json", "select 1"), null);
            fail("expected a conversion error");
        } catch (Exception e) {
            assertTrue(e instanceof VendorModelConversionException);
            assertTrue(e.getMessage().contains("JSON"));
        }
    }

    @Test
    public void manifestWithoutModelsIsRejected() {
        try {
            converter.convert(VendorModelFile.single("manifest.json", "{\"metadata\":{}}"), null);
            fail("expected a conversion error");
        } catch (Exception e) {
            assertTrue(e instanceof VendorModelConversionException);
        }
    }
}
