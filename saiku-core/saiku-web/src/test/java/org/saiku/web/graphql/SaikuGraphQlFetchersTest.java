/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.graphql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;

/**
 * Unit tests for {@link SaikuGraphQlFetchers}. The {@link DataFetchingEnvironment} is a
 * {@code Proxy} backed by a plain map, mirroring the hand-rolled stub convention (no Mockito).
 */
public class SaikuGraphQlFetchersTest {

    private SaikuGraphQlFetchers fetchers;

    @Before
    public void setUp() {
        fetchers = new SaikuGraphQlFetchers(new ObjectMapper());
        fetchers.setServerVersion("4.8.0");
    }

    @SuppressWarnings("unchecked")
    private static <T> DataFetchingEnvironment env(Map<String, Object> arguments) {
        return (DataFetchingEnvironment) ProxyEnv.create(new HashMap<>(arguments));
    }

    private static final class ProxyEnv implements java.lang.reflect.InvocationHandler {
        private final Map<String, Object> args;

        private ProxyEnv(Map<String, Object> args) {
            this.args = args;
        }

        static DataFetchingEnvironment create(Map<String, Object> args) {
            return (DataFetchingEnvironment) java.lang.reflect.Proxy.newProxyInstance(
                    SaikuGraphQlFetchersTest.class.getClassLoader(),
                    new Class<?>[] {DataFetchingEnvironment.class},
                    new ProxyEnv(args));
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] a) {
            if ("getArgument".equals(method.getName())) {
                return args.get((String) a[0]);
            }
            if ("containsArgument".equals(method.getName())) {
                return args.containsKey(a[0]);
            }
            return null;
        }
    }

    @Test
    public void serverInfo_reports_the_wired_surfaces() throws Exception {
        DataFetcher<Map<String, Object>> f = fetchers.serverInfo();
        Map<String, Object> info = f.get(env(Map.of()));
        assertEquals("4.8.0", info.get("version"));
        assertEquals(false, info.get("mdxEnabled"));
        assertEquals(false, info.get("ossieEnabled"));
    }

    @Test
    public void cubes_is_empty_without_a_metadata_service() throws Exception {
        assertTrue(fetchers.cubes().get(env(Map.of())).isEmpty());
    }

    private static final class StubMetadata extends OlapAiCubeMetadataService {
        @Override
        public List<AiCubeSummary> listCubes() {
            AiCubeSummary s = new AiCubeSummary();
            s.setConnectionName("conn");
            s.setCatalog("cat");
            s.setSchema("sch");
            s.setCubeName("Sales");
            return List.of(s);
        }

        @Override
        public AiSchema getSchema(AiCubeRef ref) {
            return new AiSchema("id", "Sales", "[conn].[cat].[sch].[Sales]");
        }
    }

    @Test
    public void cubes_lists_the_cube_estate() throws Exception {
        fetchers.setCubeMetadataService(new StubMetadata());
        List<Map<String, Object>> cubes = fetchers.cubes().get(env(Map.of()));
        assertEquals(1, cubes.size());
        assertEquals("Sales", cubes.get(0).get("cubeName"));
    }

    @Test
    public void cube_requires_a_metadata_service() throws Exception {
        try {
            fetchers.cube().get(env(Map.of("connectionName", "c")));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void cube_serves_the_resolved_schema() throws Exception {
        fetchers.setCubeMetadataService(new StubMetadata());
        Object schema = fetchers.cube()
                .get(env(Map.of(
                        "connectionName", "conn",
                        "catalog", "cat",
                        "schema", "sch",
                        "cubeName", "Sales")));
        Map<?, ?> map = (Map<?, ?>) schema;
        assertEquals("Sales", map.get("cubeName"));
    }

    @Test
    public void ossie_models_are_empty_without_services() throws Exception {
        assertTrue(fetchers.ossieModels().get(env(Map.of())).isEmpty());
    }

    private static final class StubDiscover extends org.saiku.service.olap.OlapDiscoverService {
        @Override
        public List<SaikuConnection> getAllConnections() {
            SaikuConnection ossie = new SaikuConnection("ossie-conn", List.of(), SaikuConnection.TYPE_OSSIE);
            SaikuConnection plain = new SaikuConnection("plain-conn", List.of(), "MONDRIAN");
            return List.of(ossie, plain);
        }
    }

    private static final class StubOssieDiscover extends OssieDiscoverService {
        @Override
        public OssieModelDto getModel(String connectionName) {
            OssieModelDto dto = new OssieModelDto();
            dto.setName("retail_model");
            OssieModelDto.Dataset fact = new OssieModelDto.Dataset();
            fact.setName("fact_sales");
            dto.setDatasets(List.of(fact));
            dto.setMetrics(List.of());
            return dto;
        }
    }

    @Test
    public void ossie_models_summarise_each_ossie_connection() throws Exception {
        fetchers.setOlapDiscoverService(new StubDiscover());
        fetchers.setOssieDiscoverService(new StubOssieDiscover());
        List<Map<String, Object>> models = fetchers.ossieModels().get(env(Map.of()));
        assertEquals(1, models.size());
        assertEquals("ossie-conn", models.get(0).get("connectionName"));
        assertEquals("retail_model", models.get(0).get("modelName"));
        assertEquals("fact_sales", models.get(0).get("factDataset"));
        assertEquals(0, models.get(0).get("metricCount"));
    }

    @Test
    public void a_failing_ossie_model_still_yields_a_summary_row() throws Exception {
        fetchers.setOlapDiscoverService(new StubDiscover());
        fetchers.setOssieDiscoverService(new OssieDiscoverService() {
            @Override
            public OssieModelDto getModel(String connectionName) {
                throw new IllegalStateException("boom");
            }
        });
        List<Map<String, Object>> models = fetchers.ossieModels().get(env(Map.of()));
        assertEquals(1, models.size());
        assertEquals(null, models.get(0).get("modelName"));
        assertEquals(0, models.get(0).get("metricCount"));
    }

    @Test
    public void ossie_model_requires_a_discover_service() throws Exception {
        try {
            fetchers.ossieModel().get(env(Map.of("connection", "c")));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void ossie_model_serves_the_model_dto() throws Exception {
        fetchers.setOssieDiscoverService(new StubOssieDiscover());
        Map<?, ?> model = (Map<?, ?>) fetchers.ossieModel().get(env(Map.of("connection", "ossie-conn")));
        assertEquals("retail_model", model.get("name"));
    }

    private static final class StubQueryResource extends org.saiku.web.rest.resources.AiQueryResource {
        @Override
        public Response executeAi(org.saiku.service.olap.ai.AiQueryRequest req, String format) {
            return Response.ok(Map.of("ok", true)).build();
        }
    }

    private static final class StubOssieResource extends org.saiku.web.rest.resources.AiOssieResource {
        @Override
        public Response executeAi(OssieAiQueryRequest req, String format) {
            return Response.ok(Map.of("ossie", true)).build();
        }
    }

    private static final class AllowGuard extends AiPolicyGuard {
        AllowGuard() {
            super();
        }

        @Override
        public void assertCanSend(AiDataKind kind) {}
    }

    @Test
    public void execute_mdx_requires_the_query_surface() throws Exception {
        try {
            fetchers.executeMdx().get(env(Map.of()));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void execute_mdx_delegates_to_the_ai_query_resource() throws Exception {
        fetchers.setAiQueryResource(new StubQueryResource());
        Object result = fetchers.executeMdx().get(env(Map.of("input", Map.of("limit", 1))));
        assertNotNull(result);
        assertEquals(true, ((Map<?, ?>) result).get("ok"));
    }

    @Test
    public void execute_ossie_requires_the_ossie_surface() throws Exception {
        try {
            fetchers.executeOssie().get(env(Map.of()));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void execute_ossie_delegates_to_the_ossie_resource() throws Exception {
        fetchers.setAiOssieResource(new StubOssieResource());
        fetchers.setAiPolicyGuard(new AllowGuard());
        Object result = fetchers.executeOssie().get(env(Map.of("input", Map.of("limit", 1))));
        assertEquals(true, ((Map<?, ?>) result).get("ossie"));
    }
}
