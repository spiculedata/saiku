/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.RepositoryDatasourceManager;

/**
 * Unit tests for {@link OssieDiscoverService}: build a datasource with an Ossie YAML on disk,
 * install a stub {@link RepositoryDatasourceManager} that overrides only the lookup we care
 * about, and verify the projected {@link OssieModelDto} shape. Using a subclass rather than a
 * mocking framework (Mockito isn't on this module's test classpath) or a hand-rolled interface
 * stub (the {@code IDatasourceManager} surface is huge — 40+ methods).
 */
public class OssieDiscoverServiceTest {

    private OssieDiscoverService service;
    private StubDatasourceManager datasourceManager;
    private Path yaml;

    @Before
    public void setUp() throws Exception {
        yaml = Files.createTempFile("ossie-discover-", ".yaml");
        Files.writeString(
                yaml,
                "version: 0.2.0.dev0\n"
                        + "semantic_model:\n"
                        + "- name: SALES\n"
                        + "  datasets:\n"
                        + "  - name: CUSTOMERS\n"
                        + "    source: public.customers\n"
                        + "    primary_key: [id]\n"
                        + "    fields:\n"
                        + "    - name: id\n"
                        + "      expression:\n"
                        + "        dialects:\n"
                        + "        - dialect: ANSI_SQL\n"
                        + "          expression: id\n"
                        + "    - name: region\n"
                        + "      expression:\n"
                        + "        dialects:\n"
                        + "        - dialect: ANSI_SQL\n"
                        + "          expression: region\n"
                        + "      description: Sales region\n"
                        + "    - name: signup_date\n"
                        + "      expression:\n"
                        + "        dialects:\n"
                        + "        - dialect: ANSI_SQL\n"
                        + "          expression: signup_date\n"
                        + "      dimension:\n"
                        + "        is_time: true\n"
                        + "    - name: full_name\n"
                        + "      expression:\n"
                        + "        dialects:\n"
                        + "        - dialect: ANSI_SQL\n"
                        + "          expression: full_name\n"
                        + "      custom_extensions:\n"
                        + "      - vendor_name: SAIKU\n"
                        + "        data: '{\"pii\":true}'\n"
                        + "  - name: ORDERS\n"
                        + "    source: public.orders\n"
                        + "    fields: []\n"
                        + "  metrics:\n"
                        + "  - name: revenue\n"
                        + "    expression:\n"
                        + "      dialects:\n"
                        + "      - dialect: ANSI_SQL\n"
                        + "        expression: SUM(orders.amount)\n"
                        + "    custom_extensions:\n"
                        + "    - vendor_name: SAIKU\n"
                        + "      data: '{\"aggregation_kind\":\"SUM\"}'\n"
                        + "  relationships:\n"
                        + "  - name: orders_to_customers\n"
                        + "    from: ORDERS\n"
                        + "    to: CUSTOMERS\n"
                        + "    from_columns: [customer_id]\n"
                        + "    to_columns: [id]\n");
        datasourceManager = new StubDatasourceManager();
        service = new OssieDiscoverService();
        service.setDatasourceManager(datasourceManager);
    }

    @After
    public void tearDown() throws Exception {
        if (yaml != null) Files.deleteIfExists(yaml);
    }

    @Test
    public void projectsSemanticModelIntoDto() {
        datasourceManager.put(
                "SALES",
                ossieDatasource("SALES", propsOf(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString(), "schema", "SALES")));

        OssieModelDto dto = service.getModel("SALES");

        assertEquals("SALES", dto.getConnection());
        assertEquals("SALES", dto.getName());
        assertEquals(2, dto.getDatasets().size());
        assertEquals("CUSTOMERS", dto.getDatasets().get(0).getName());
        assertEquals("public.customers", dto.getDatasets().get(0).getSource());
        assertEquals(4, dto.getDatasets().get(0).getFields().size());

        OssieModelDto.Field signup = dto.getDatasets().get(0).getFields().get(2);
        assertEquals("signup_date", signup.getName());
        assertTrue("is_time flag must survive", signup.isTime());
        assertFalse(signup.isPii());

        OssieModelDto.Field fullName = dto.getDatasets().get(0).getFields().get(3);
        assertTrue("PII custom_extensions must project through", fullName.isPii());

        assertEquals(1, dto.getMetrics().size());
        OssieModelDto.Metric revenue = dto.getMetrics().get(0);
        assertEquals("revenue", revenue.getName());
        assertEquals("SUM(orders.amount)", revenue.getExpression());
        assertEquals("SUM", revenue.getAggregationKind());

        assertEquals(1, dto.getRelationships().size());
        OssieModelDto.Relationship rel = dto.getRelationships().get(0);
        assertEquals("ORDERS", rel.getFrom());
        assertEquals("CUSTOMERS", rel.getTo());
        assertEquals(List.of("customer_id"), rel.getFromColumns());
    }

    @Test
    public void missingSchemaPropertyFallsBackToFirstModel() {
        datasourceManager.put(
                "SALES", ossieDatasource("SALES", propsOf(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString())));
        OssieModelDto dto = service.getModel("SALES");
        // Only one semantic_model in the fixture — 'SALES' — so fallback returns it.
        assertEquals("SALES", dto.getName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonOssieDatasource() {
        datasourceManager.put("MDX", new SaikuDatasource("MDX", SaikuDatasource.Type.OLAP, new Properties()));
        service.getModel("MDX");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownConnection() {
        service.getModel("no-such");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingYamlProperty() {
        datasourceManager.put("SALES", ossieDatasource("SALES", new Properties()));
        service.getModel("SALES");
    }

    @Test
    public void descriptionsSurviveProjection() {
        // Belt-and-braces regression guard: a field with a description but no PII extension
        // must not get flagged as pii.
        datasourceManager.put(
                "SALES",
                ossieDatasource("SALES", propsOf(ISaikuConnection.OSSIE_YAML_KEY, yaml.toString(), "schema", "SALES")));
        OssieModelDto dto = service.getModel("SALES");
        OssieModelDto.Field region = dto.getDatasets().get(0).getFields().get(1);
        assertEquals("region", region.getName());
        assertFalse("region has description but no PII marker", region.isPii());
        assertNotNull(region.getDescription());
        assertEquals("Sales region", region.getDescription());
    }

    private static SaikuDatasource ossieDatasource(String name, Properties props) {
        return new SaikuDatasource(name, SaikuDatasource.Type.OSSIE, props);
    }

    private static Properties propsOf(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i + 1 < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    /**
     * Subclass of the real {@link RepositoryDatasourceManager} that overrides only the two
     * {@code getDatasource} lookups the discover service uses. Extending the real class rather
     * than the interface avoids stubbing the 40+ other {@code IDatasourceManager} methods.
     * Never calls {@code load()} or any repository code, so the parent's uninitialised state
     * is fine.
     */
    static final class StubDatasourceManager extends RepositoryDatasourceManager {
        private final Map<String, SaikuDatasource> map = new HashMap<>();

        void put(String name, SaikuDatasource ds) {
            map.put(name, ds);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName) {
            return map.get(datasourceName);
        }

        @Override
        public SaikuDatasource getDatasource(String datasourceName, boolean refresh) {
            return map.get(datasourceName);
        }
    }
}
