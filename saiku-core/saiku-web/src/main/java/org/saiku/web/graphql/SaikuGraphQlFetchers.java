/*
 *   Copyright 2026 Spicule Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.web.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiCubeSummary;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.web.rest.resources.AiOssieResource;
import org.saiku.web.rest.resources.AiQueryResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data fetchers for {@code SaikuGraphQlService}.
 *
 * <p>These are the resolver bodies for every top-level {@code Query} field the SDL declares.
 * Rather than duplicate the (extensive) AiQueryResource / AiOssieResource execution logic
 * — policy guards, drillthrough registration, error translation, cellset marshalling — this
 * class delegates to those resources directly. Because they're request-scoped Spring beans
 * with the {@code aop:scoped-proxy} wiring, Spring hands GraphQL a proxy that resolves to
 * the current request's instance; the GraphQL execution runs on the servlet request thread
 * so the scope is live.
 *
 * <p>The trade-off: GraphQL responses inherit the REST envelope shapes (AiQueryResponse,
 * OssieAiQueryResponse). Codegen consumers see them as {@code JSON} in SDL — see the
 * comments on the {@code executeMdx} / {@code executeOssie} fields in {@code saiku.graphqls}.
 */
public class SaikuGraphQlFetchers {

    private static final Logger LOG = LoggerFactory.getLogger(SaikuGraphQlFetchers.class);

    private final ObjectMapper mapper;
    private OlapAiCubeMetadataService cubeMetadataService;
    private OlapDiscoverService olapDiscoverService;
    private OssieDiscoverService ossieDiscoverService;
    private AiQueryResource aiQueryResource;
    private AiOssieResource aiOssieResource;
    private AiPolicyGuard aiPolicyGuard;
    private String serverVersion = "unknown";

    public SaikuGraphQlFetchers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------
    // Injection points (Spring XML properties)
    // ------------------------------------------------------------

    public void setCubeMetadataService(OlapAiCubeMetadataService s) {
        this.cubeMetadataService = s;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    public void setOssieDiscoverService(OssieDiscoverService s) {
        this.ossieDiscoverService = s;
    }

    public void setAiQueryResource(AiQueryResource r) {
        this.aiQueryResource = r;
    }

    public void setAiOssieResource(AiOssieResource r) {
        this.aiOssieResource = r;
    }

    /**
     * Optional policy guard. When wired, {@link #executeMdx()}, {@link #executeOssie()}, and
     * per-cube fetchers assert the {@link AiDataKind#AGGREGATED_RESULT_VALUES} data kind before
     * dispatching. The delegated REST resources check this again themselves — this call makes
     * the enforcement visible in the GraphQL audit trail rather than only inheriting it
     * transitively.
     */
    public void setAiPolicyGuard(AiPolicyGuard g) {
        this.aiPolicyGuard = g;
    }

    public void setServerVersion(String v) {
        this.serverVersion = v;
    }

    /** Small helper so the execute fetchers stay one-liner. */
    private void assertQueryAllowed() {
        if (aiPolicyGuard != null) {
            aiPolicyGuard.assertCanSend(AiDataKind.AGGREGATED_RESULT_VALUES);
        }
    }

    // ------------------------------------------------------------
    // Metadata fetchers
    // ------------------------------------------------------------

    public DataFetcher<Map<String, Object>> serverInfo() {
        return env -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("version", serverVersion);
            info.put("mdxEnabled", cubeMetadataService != null);
            info.put("ossieEnabled", ossieDiscoverService != null);
            return info;
        };
    }

    public DataFetcher<List<Map<String, Object>>> cubes() {
        return env -> {
            if (cubeMetadataService == null) {
                return List.of();
            }
            List<AiCubeSummary> raw = cubeMetadataService.listCubes();
            List<Map<String, Object>> out = new ArrayList<>(raw.size());
            for (AiCubeSummary c : raw) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("connectionName", c.getConnectionName());
                row.put("catalog", c.getCatalog());
                row.put("schema", c.getSchema());
                row.put("cubeName", c.getCubeName());
                out.add(row);
            }
            return out;
        };
    }

    public DataFetcher<Object> cube() {
        return env -> {
            if (cubeMetadataService == null) {
                throw new IllegalStateException("MDX cube surface is not configured");
            }
            AiCubeRef ref = new AiCubeRef();
            ref.setConnectionName(env.getArgument("connectionName"));
            ref.setCatalog(env.getArgument("catalog"));
            ref.setSchema(env.getArgument("schema"));
            ref.setCubeName(env.getArgument("cubeName"));
            AiSchema schema = cubeMetadataService.getSchema(ref);
            return mapper.convertValue(schema, Map.class);
        };
    }

    public DataFetcher<List<Map<String, Object>>> ossieModels() {
        return env -> {
            if (olapDiscoverService == null || ossieDiscoverService == null) {
                return List.of();
            }
            // Mirrors AiOssieResource.listModels — iterates the connection registry, keeps
            // OSSIE-typed connections, resolves each via the discover service so callers see
            // the inner semantic_model name (which is what /query needs, not just the
            // connection name).
            List<Map<String, Object>> out = new ArrayList<>();
            for (SaikuConnection c : olapDiscoverService.getAllConnections()) {
                if (!SaikuConnection.TYPE_OSSIE.equals(c.getType())) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("connectionName", c.getName());
                try {
                    OssieModelDto model = ossieDiscoverService.getModel(c.getName());
                    summary.put("modelName", model.getName());
                    String fact = null;
                    for (OssieModelDto.Dataset ds : model.getDatasets()) {
                        if (ds.getName() != null && ds.getName().toLowerCase().startsWith("fact")) {
                            fact = ds.getName();
                            break;
                        }
                    }
                    if (fact == null && !model.getDatasets().isEmpty()) {
                        fact = model.getDatasets().get(0).getName();
                    }
                    summary.put("factDataset", fact);
                    summary.put("datasetCount", model.getDatasets().size());
                    summary.put("metricCount", model.getMetrics().size());
                } catch (RuntimeException listErr) {
                    summary.put("modelName", null);
                    summary.put("factDataset", null);
                    summary.put("datasetCount", 0);
                    summary.put("metricCount", 0);
                }
                out.add(summary);
            }
            return out;
        };
    }

    public DataFetcher<Object> ossieModel() {
        return env -> {
            if (ossieDiscoverService == null) {
                throw new IllegalStateException("Ossie surface is not configured");
            }
            String connection = env.getArgument("connection");
            OssieModelDto dto = ossieDiscoverService.getModel(connection);
            return mapper.convertValue(dto, Map.class);
        };
    }

    // ------------------------------------------------------------
    // Execution fetchers — delegate to the REST resources
    // ------------------------------------------------------------

    public DataFetcher<Object> executeMdx() {
        return env -> {
            if (aiQueryResource == null) {
                throw new IllegalStateException("MDX execution surface is not configured");
            }
            assertQueryAllowed();
            Map<String, Object> input = env.getArgument("input");
            AiQueryRequest req = mapper.convertValue(input, AiQueryRequest.class);
            Response resp = aiQueryResource.executeAi(req, "records");
            return unwrap(resp);
        };
    }

    public DataFetcher<Object> executeOssie() {
        return env -> {
            if (aiOssieResource == null) {
                throw new IllegalStateException("Ossie execution surface is not configured");
            }
            assertQueryAllowed();
            Map<String, Object> input = env.getArgument("input");
            OssieAiQueryRequest req = mapper.convertValue(input, OssieAiQueryRequest.class);
            Response resp = aiOssieResource.executeAi(req, "records");
            return unwrap(resp);
        };
    }

    // ------------------------------------------------------------
    // Schema-per-cube — enumerate the cube estate and produce one CubeTypeGenerator each.
    // Called from SaikuGraphQlService.rebuild().
    // ------------------------------------------------------------

    /**
     * Enumerate every cube visible via {@code cubeMetadataService}, resolve its {@code AiSchema},
     * assign a unique GraphQL field name (collision-safe within this schema), and hand back a
     * {@code Map<queryFieldName, CubeTypeGenerator>}.
     *
     * <p>Failure to resolve one cube's schema doesn't stop the enumeration — the misbehaving
     * cube is logged and skipped. The generic {@code executeMdx} field always covers it.
     */
    Map<String, CubeTypeGenerator> buildCubeGenerators() {
        Map<String, CubeTypeGenerator> out = new LinkedHashMap<>();
        if (cubeMetadataService == null) {
            return out;
        }
        java.util.Set<String> takenFieldNames = new java.util.HashSet<>(java.util.Set.of(
                "serverInfo", "cubes", "cube", "ossieModels", "ossieModel", "executeMdx", "executeOssie"));
        List<AiCubeSummary> summaries;
        try {
            summaries = cubeMetadataService.listCubes();
        } catch (RuntimeException e) {
            LOG.warn("listCubes failed during schema generation: {}", e.toString());
            return out;
        }
        for (AiCubeSummary c : summaries) {
            AiCubeRef ref = new AiCubeRef();
            ref.setConnectionName(c.getConnectionName());
            ref.setCatalog(c.getCatalog());
            ref.setSchema(c.getSchema());
            ref.setCubeName(c.getCubeName());
            AiSchema schema;
            try {
                schema = cubeMetadataService.getSchema(ref);
            } catch (RuntimeException schemaErr) {
                LOG.warn("skipping cube '{}' — schema unresolved: {}", c.getCubeName(), schemaErr.toString());
                continue;
            }
            String fieldName = uniqueFieldName(takenFieldNames, c);
            takenFieldNames.add(fieldName);
            out.put(fieldName, new CubeTypeGenerator(c, schema, fieldName));
        }
        return out;
    }

    /**
     * Per-cube data fetcher. Reads the enum-typed measure and level arguments, converts them back
     * to the canonical names, builds an {@link AiQueryRequest}, delegates to
     * {@link AiQueryResource#executeAi}, and marshals the row records onto the typed shape the
     * generator declared.
     */
    DataFetcher<Object> executeCubeField(CubeTypeGenerator gen) {
        return env -> {
            if (aiQueryResource == null) {
                throw new IllegalStateException("MDX execution surface is not configured");
            }
            assertQueryAllowed();
            @SuppressWarnings("unchecked")
            List<String> measureEnums = (List<String>) env.getArgument("measures");
            @SuppressWarnings("unchecked")
            List<String> rowEnums = (List<String>) env.getArgument("rows");
            @SuppressWarnings("unchecked")
            List<String> columnEnums = (List<String>) env.getArgument("columns");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filters = (List<Map<String, Object>>) env.getArgument("filters");
            Integer limit = env.getArgument("limit");

            if (measureEnums == null || measureEnums.isEmpty()) {
                throw new IllegalArgumentException("measures is required and cannot be empty");
            }

            List<String> canonicalMeasures = new ArrayList<>(measureEnums.size());
            List<Map<String, String>> measurePayload = new ArrayList<>(measureEnums.size());
            for (String enumValue : measureEnums) {
                String canonical = gen.measureEnumToCanonical(enumValue);
                if (canonical == null) {
                    throw new IllegalArgumentException("unknown measure enum: " + enumValue);
                }
                canonicalMeasures.add(canonical);
                measurePayload.add(Map.of("name", canonical));
            }

            List<CubeTypeGenerator.AxisRef> selectedRows = resolveAxes(gen, rowEnums);
            List<CubeTypeGenerator.AxisRef> selectedColumns = resolveAxes(gen, columnEnums);

            Map<String, Object> requestJson = new LinkedHashMap<>();
            Map<String, String> cubeRefJson = new LinkedHashMap<>();
            org.saiku.service.olap.ai.AiCubeRef ref = gen.toCubeRef();
            cubeRefJson.put("connectionName", ref.getConnectionName());
            cubeRefJson.put("catalog", ref.getCatalog());
            cubeRefJson.put("schema", ref.getSchema());
            cubeRefJson.put("cubeName", ref.getCubeName());
            requestJson.put("cube", cubeRefJson);
            requestJson.put("measures", measurePayload);
            requestJson.put("rows", axesToJson(selectedRows));
            requestJson.put("columns", axesToJson(selectedColumns));
            if (filters != null && !filters.isEmpty()) requestJson.put("filters", filters);
            if (limit != null && limit > 0) requestJson.put("limit", limit);

            AiQueryRequest req = mapper.convertValue(requestJson, AiQueryRequest.class);
            Response resp = aiQueryResource.executeAi(req, "records");
            Object payload = unwrap(resp);
            return materialiseRows(payload, gen, canonicalMeasures, selectedRows, selectedColumns);
        };
    }

    private static List<CubeTypeGenerator.AxisRef> resolveAxes(CubeTypeGenerator gen, List<String> enums) {
        List<CubeTypeGenerator.AxisRef> out = new ArrayList<>();
        if (enums == null) return out;
        for (String enumValue : enums) {
            CubeTypeGenerator.AxisRef axis = gen.levelEnumToAxis(enumValue);
            if (axis == null) {
                throw new IllegalArgumentException("unknown level enum: " + enumValue);
            }
            out.add(axis);
        }
        return out;
    }

    private static List<Map<String, String>> axesToJson(List<CubeTypeGenerator.AxisRef> axes) {
        List<Map<String, String>> out = new ArrayList<>(axes.size());
        for (CubeTypeGenerator.AxisRef a : axes) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("dimension", a.dimension);
            m.put("hierarchy", a.hierarchy);
            m.put("level", a.level);
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> materialiseRows(
            Object payload,
            CubeTypeGenerator gen,
            List<String> selectedMeasures,
            List<CubeTypeGenerator.AxisRef> selectedRows,
            List<CubeTypeGenerator.AxisRef> selectedColumns) {
        if (!(payload instanceof Map)) return List.of();
        Map<String, Object> envelope = (Map<String, Object>) payload;
        Object data = envelope.get("data");
        if (!(data instanceof List)) return List.of();
        List<Object> raw = (List<Object>) data;
        List<CubeTypeGenerator.AxisRef> combined = new ArrayList<>(selectedRows);
        combined.addAll(selectedColumns);
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object row : raw) {
            if (!(row instanceof Map)) continue;
            out.add(gen.materialiseRow((Map<String, Object>) row, selectedMeasures, combined));
        }
        return out;
    }

    private static String uniqueFieldName(java.util.Set<String> taken, AiCubeSummary c) {
        String base = CubeTypeGenerator.camelCase(c.getCubeName());
        if (!taken.contains(base)) return base;
        // Collide: prepend schema, then catalog, then connection.
        String withSchema = CubeTypeGenerator.camelCase(c.getSchema() + " " + c.getCubeName());
        if (!taken.contains(withSchema)) return withSchema;
        String withCatalog = CubeTypeGenerator.camelCase(c.getCatalog() + " " + c.getSchema() + " " + c.getCubeName());
        if (!taken.contains(withCatalog)) return withCatalog;
        String full = CubeTypeGenerator.camelCase(
                c.getConnectionName() + " " + c.getCatalog() + " " + c.getSchema() + " " + c.getCubeName());
        // Final fallback — append counter until unique.
        int i = 2;
        String out = full;
        while (taken.contains(out)) {
            out = full + i++;
        }
        return out;
    }

    /**
     * Extract the payload from a JAX-RS Response for return through GraphQL. Non-2xx responses
     * from the underlying resource become GraphQL errors with the payload preserved as extensions.
     */
    private Object unwrap(Response resp) {
        Object entity = resp.getEntity();
        int status = resp.getStatus();
        if (status >= 200 && status < 300) {
            return mapper.convertValue(entity, Object.class);
        }
        // Wrap the REST error payload so a caller can still read the field/available details.
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", status);
        err.put("body", mapper.convertValue(entity, Object.class));
        throw new GraphQlUpstreamException("upstream returned HTTP " + status, err);
    }
}
