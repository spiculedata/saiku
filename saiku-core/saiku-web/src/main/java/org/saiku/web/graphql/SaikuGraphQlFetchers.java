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
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.OlapAiCubeMetadataService;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.web.rest.resources.AiOssieResource;
import org.saiku.web.rest.resources.AiQueryResource;

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

    private final ObjectMapper mapper;
    private OlapAiCubeMetadataService cubeMetadataService;
    private OlapDiscoverService olapDiscoverService;
    private OssieDiscoverService ossieDiscoverService;
    private AiQueryResource aiQueryResource;
    private AiOssieResource aiOssieResource;
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

    public void setServerVersion(String v) {
        this.serverVersion = v;
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
            Map<String, Object> input = env.getArgument("input");
            OssieAiQueryRequest req = mapper.convertValue(input, OssieAiQueryRequest.class);
            Response resp = aiOssieResource.executeAi(req, "records");
            return unwrap(resp);
        };
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
