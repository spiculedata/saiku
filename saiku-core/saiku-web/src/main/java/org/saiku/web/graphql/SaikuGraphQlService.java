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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLSchema;
import graphql.schema.StaticDataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Central GraphQL engine for Saiku.
 *
 * <p>Loads the SDL from {@code graphql/saiku.graphqls} at bean init time, wires a
 * {@link RuntimeWiring} whose data fetchers delegate to the same MDX + Ossie services
 * the REST + AI query resources use, and exposes a single {@link #execute(String, Map, String)}
 * entry point for the JAX-RS transport.
 *
 * <p>Schema is built once at boot. If the operator adds a cube through the admin UI
 * the top-level {@code cubes} query surfaces it immediately (the fetcher reads live),
 * but the SDL itself is static — that's the trade-off we picked for the initial slice
 * over Cube-style schema-per-cube, which would need a schema-invalidation dance on every
 * {@code /admin/discover/refresh}. Future iteration: cube-per-Query-field lives on top.
 */
public class SaikuGraphQlService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SaikuGraphQlService.class);

    private static final String SDL_CLASSPATH = "graphql/saiku.graphqls";

    private final ObjectMapper mapper;
    private SaikuGraphQlFetchers fetchers;

    private String sdl;
    private GraphQLSchema schema;
    private GraphQL graphql;

    public SaikuGraphQlService() {
        this(new ObjectMapper());
    }

    public SaikuGraphQlService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void setFetchers(SaikuGraphQlFetchers fetchers) {
        this.fetchers = fetchers;
    }

    @Override
    public void afterPropertiesSet() {
        if (fetchers == null) {
            throw new IllegalStateException("SaikuGraphQlService requires a SaikuGraphQlFetchers bean");
        }
        this.sdl = loadSdl();
        this.schema = buildSchema(sdl, fetchers);
        this.graphql = GraphQL.newGraphQL(schema).build();
        log.info(
                "Saiku GraphQL engine initialised — {} top-level query fields",
                schema.getQueryType().getFieldDefinitions().size());
    }

    /**
     * Execute a GraphQL request.
     *
     * @param query the GraphQL query text (required)
     * @param variables optional variable bindings; may be {@code null}
     * @param operationName optional operation name for multi-op documents; may be {@code null}
     * @return the standard {@code {data, errors, extensions}} envelope as a Map — Jackson-ready
     */
    public Map<String, Object> execute(String query, Map<String, Object> variables, String operationName) {
        if (query == null || query.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", "query text is required");
            err.put("extensions", Map.of("classification", "ValidationError"));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("data", null);
            envelope.put("errors", java.util.List.of(err));
            return envelope;
        }
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput().query(query);
        if (variables != null && !variables.isEmpty()) {
            input.variables(variables);
        }
        if (operationName != null && !operationName.isBlank()) {
            input.operationName(operationName);
        }
        ExecutionResult result = graphql.execute(input.build());
        return result.toSpecification();
    }

    /** Raw SDL — used by the {@code /schema.graphql} endpoint. */
    public String getSdl() {
        return sdl;
    }

    private static String loadSdl() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(SDL_CLASSPATH)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + SDL_CLASSPATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load SDL " + SDL_CLASSPATH, e);
        }
    }

    private static GraphQLSchema buildSchema(String sdl, SaikuGraphQlFetchers fetchers) {
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .scalar(ExtendedScalars.Json)
                .type("Query", builder -> builder.dataFetcher("serverInfo", fetchers.serverInfo())
                        .dataFetcher("cubes", fetchers.cubes())
                        .dataFetcher("cube", fetchers.cube())
                        .dataFetcher("ossieModels", fetchers.ossieModels())
                        .dataFetcher("ossieModel", fetchers.ossieModel())
                        .dataFetcher("executeMdx", fetchers.executeMdx())
                        .dataFetcher("executeOssie", fetchers.executeOssie()))
                .build();
        // Ensure the wiring builder actually gets used; SchemaGenerator wires missing fields
        // to a StaticDataFetcher that returns null, which would be a silent hole in coverage.
        @SuppressWarnings("unused")
        StaticDataFetcher sentinel = new StaticDataFetcher(null);
        return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
    }

    /** Introspection helper — expose the ObjectMapper the fetchers should use to build JSON payloads. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Introspection helper — used by unit tests that want to poke at the wired schema without
     * going through the full HTTP transport.
     */
    public GraphQLSchema getSchema() {
        return schema;
    }

    /**
     * Introspection helper — coerce an arbitrary POJO/CellDataSet to a JsonNode.
     * Kept here so the fetchers don't each have to hold a reference to the mapper.
     */
    JsonNode toJson(Object value) {
        return mapper.valueToTree(value);
    }
}
