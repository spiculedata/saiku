/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.saiku.service.ossie.OssieModelDto;

/** Unit tests for {@link SupersetDatasetYamlExporter} — unpacks the zip and parses each YAML entry. */
public class SupersetDatasetYamlExporterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    public void bundleContainsMetadataDatabaseAndOneDatasetPerTable() throws Exception {
        OssieModelDto model = fixture();
        SemanticExportResult result = new SupersetDatasetYamlExporter().export(model);

        assertEquals("pharma-superset-export.zip", result.getFilename());
        assertEquals("application/zip", result.getContentType());

        Map<String, String> entries = unzip(result.getContent());
        assertTrue(entries.containsKey("metadata.yaml"));
        assertTrue(entries.containsKey("databases/pharma.yaml"));
        assertTrue(entries.containsKey("datasets/pharma/fact_pharma.yaml"));
        assertTrue(entries.containsKey("datasets/pharma/customers.yaml"));

        Map<String, Object> db = yaml.readValue(entries.get("databases/pharma.yaml"), Map.class);
        assertEquals("Pharma", db.get("database_name"));
        assertNotNull(db.get("uuid"));

        Map<String, Object> factDs = yaml.readValue(entries.get("datasets/pharma/fact_pharma.yaml"), Map.class);
        assertEquals("fact_pharma", factDs.get("table_name"));
        assertEquals(db.get("uuid"), factDs.get("database_uuid"));

        List<Map<String, Object>> metrics = (List<Map<String, Object>>) factDs.get("metrics");
        assertEquals(1, metrics.size());
        assertEquals("net_revenue", metrics.get(0).get("metric_name"));
        // Own-dataset reference is unwrapped to a bare column name.
        assertEquals("SUM(NETREVENUE)", metrics.get(0).get("expression"));

        List<Map<String, Object>> columns = (List<Map<String, Object>>) factDs.get("columns");
        assertEquals(1, columns.size());
        assertEquals("NETREVENUE", columns.get(0).get("column_name"));

        Map<String, Object> customersDs = yaml.readValue(entries.get("datasets/pharma/customers.yaml"), Map.class);
        // Metric belongs to fact_pharma, not customers.
        assertEquals(List.of(), customersDs.get("metrics"));
    }

    @Test
    public void timeFieldMarksIsDttmAndMainDttmCol() throws Exception {
        OssieModelDto model = fixture();
        model.getDatasets().get(1).getFields().get(0).setTime(true);
        SemanticExportResult result = new SupersetDatasetYamlExporter().export(model);
        Map<String, String> entries = unzip(result.getContent());
        Map<String, Object> customersDs = yaml.readValue(entries.get("datasets/pharma/customers.yaml"), Map.class);
        assertEquals("REGION", customersDs.get("main_dttm_col"));
        List<Map<String, Object>> columns = (List<Map<String, Object>>) customersDs.get("columns");
        assertEquals(Boolean.TRUE, columns.get(0).get("is_dttm"));
    }

    @Test
    public void rejectsModelWithNoDatasets() {
        OssieModelDto empty = new OssieModelDto();
        empty.setName("Empty");
        assertThrows(SemanticExportException.class, () -> new SupersetDatasetYamlExporter().export(empty));
    }

    private Map<String, String> unzip(byte[] content) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private OssieModelDto fixture() {
        OssieModelDto model = new OssieModelDto();
        model.setName("Pharma");
        model.setConnection("Pharma");

        OssieModelDto.Dataset fact = new OssieModelDto.Dataset();
        fact.setName("fact_pharma");
        fact.setSource("FACT_PHARMA");
        OssieModelDto.Field netrev = new OssieModelDto.Field();
        netrev.setName("NETREVENUE");
        fact.getFields().add(netrev);
        model.getDatasets().add(fact);

        OssieModelDto.Dataset customers = new OssieModelDto.Dataset();
        customers.setName("customers");
        customers.setSource("DIM_CUSTOMERS");
        OssieModelDto.Field region = new OssieModelDto.Field();
        region.setName("REGION");
        customers.getFields().add(region);
        model.getDatasets().add(customers);

        OssieModelDto.Metric netRev = new OssieModelDto.Metric();
        netRev.setName("net_revenue");
        netRev.setExpression("SUM(\"fact_pharma\".\"NETREVENUE\")");
        model.getMetrics().add(netRev);
        return model;
    }
}
