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
 * <p>Schema is built once at boot AND on demand via {@link #refresh()} so operators can
 * pick up new cubes added through the admin UI without a webapp restart. The typical wire
 * path is: admin POST /admin/discover/refresh → OlapDiscoverService reloads → someone calls
 * {@code refresh()} on this bean (an admin endpoint hangs off {@link GraphQlResource}).
 */
public class SaikuGraphQlService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SaikuGraphQlService.class);

    private static final String SDL_CLASSPATH = "graphql/saiku.graphqls";

    private final ObjectMapper mapper;
    private SaikuGraphQlFetchers fetchers;
    private PersistedQueryCache persistedQueryCache = new PersistedQueryCache();

    private volatile String sdl;
    private volatile GraphQLSchema schema;
    private volatile GraphQL graphql;

    public SaikuGraphQlService() {
        this(new ObjectMapper());
    }

    public SaikuGraphQlService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void setFetchers(SaikuGraphQlFetchers fetchers) {
        this.fetchers = fetchers;
    }

    /** Override the default {@link PersistedQueryCache} — used to size the cache per-deployment. */
    public void setPersistedQueryCache(PersistedQueryCache cache) {
        if (cache != null) this.persistedQueryCache = cache;
    }

    /** Exposed so an admin refresh endpoint can wipe stale hashes when the schema changes. */
    public PersistedQueryCache getPersistedQueryCache() {
        return persistedQueryCache;
    }

    @Override
    public void afterPropertiesSet() {
        if (fetchers == null) {
            throw new IllegalStateException("SaikuGraphQlService requires a SaikuGraphQlFetchers bean");
        }
        rebuild();
    }

    /**
     * Rebuild the schema against the current cube estate. Safe to call under load — the new
     * schema is atomically swapped in once assembled, so in-flight executions finish on their
     * original engine.
     *
     * <p>Failures (bad cube, unreachable YAML) log and are dropped so a single misconfigured
     * cube can't take the whole GraphQL surface down.
     */
    public synchronized void refresh() {
        rebuild();
    }

    private void rebuild() {
        String baseSdl = loadSdl();
        StringBuilder cubeSdl = new StringBuilder();
        Map<String, CubeTypeGenerator> perCubeGenerators = new LinkedHashMap<>();
        try {
            perCubeGenerators.putAll(fetchers.buildCubeGenerators());
            for (CubeTypeGenerator gen : perCubeGenerators.values()) {
                String fragment = gen.toSdl();
                if (!fragment.isEmpty()) {
                    cubeSdl.append(fragment);
                }
            }
        } catch (RuntimeException e) {
            log.warn("cube schema enumeration failed — GraphQL will only expose the static surface: {}", e.toString());
        }

        String combined = cubeSdl.length() > 0 ? baseSdl + "\n\n" + cubeSdl : baseSdl;
        boolean sdlChanged = !combined.equals(this.sdl);
        this.sdl = combined;
        this.schema = buildSchema(combined, fetchers, perCubeGenerators);
        this.graphql = GraphQL.newGraphQL(schema).build();
        // Wipe persisted queries when the schema shape changed — stale hashes could still
        // parse against the old shape but reference dropped fields, silently returning nulls.
        if (sdlChanged) {
            persistedQueryCache.invalidateAll();
        }
        log.info(
                "Saiku GraphQL engine {} — {} top-level query fields ({} cubes typed)",
                sdl.equals(baseSdl) ? "initialised (no cubes)" : "rebuilt",
                schema.getQueryType().getFieldDefinitions().size(),
                perCubeGenerators.size());
    }

    /**
     * Execute a GraphQL request without APQ. Use {@link #execute(String, Map, String, Map)} for
     * requests that carry an {@code extensions.persistedQuery} block.
     */
    public Map<String, Object> execute(String query, Map<String, Object> variables, String operationName) {
        return execute(query, variables, operationName, null);
    }

    /**
     * Execute a GraphQL request. Handles the Apollo APQ protocol when {@code extensions} carries a
     * {@code persistedQuery} block:
     * <ul>
     *   <li>Hash + no query → cache lookup; miss returns {@code PersistedQueryNotFound}.</li>
     *   <li>Hash + query → validate hash matches {@code SHA-256(query)}; store on match; execute.</li>
     *   <li>No hash → straight execution as before.</li>
     * </ul>
     *
     * @param query the GraphQL query text (may be null when APQ hash is supplied and cached)
     * @param variables optional variable bindings; may be {@code null}
     * @param operationName optional operation name for multi-op documents; may be {@code null}
     * @param extensions optional GraphQL extensions map — {@code persistedQuery} handled here
     * @return the standard {@code {data, errors, extensions}} envelope as a Map — Jackson-ready
     */
    public Map<String, Object> execute(
            String query, Map<String, Object> variables, String operationName, Map<String, Object> extensions) {
        String resolvedQuery = query;
        String apqHash = extractPersistedQueryHash(extensions);
        if (apqHash != null) {
            if (resolvedQuery == null || resolvedQuery.isBlank()) {
                // Client sent only the hash; look it up.
                String cached = persistedQueryCache.get(apqHash);
                if (cached == null) {
                    return persistedQueryNotFound();
                }
                resolvedQuery = cached;
            } else {
                // Client sent both hash and query — store the mapping if the hash verifies.
                boolean stored = persistedQueryCache.put(apqHash, resolvedQuery);
                if (!stored) {
                    return persistedQueryMismatch();
                }
            }
        }

        if (resolvedQuery == null || resolvedQuery.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", "query text is required");
            err.put("extensions", Map.of("classification", "ValidationError"));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("data", null);
            envelope.put("errors", java.util.List.of(err));
            return envelope;
        }
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput().query(resolvedQuery);
        if (variables != null && !variables.isEmpty()) {
            input.variables(variables);
        }
        if (operationName != null && !operationName.isBlank()) {
            input.operationName(operationName);
        }
        ExecutionResult result = graphql.execute(input.build());
        return result.toSpecification();
    }

    // ------------------------------------------------------------
    // APQ helpers
    // ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String extractPersistedQueryHash(Map<String, Object> extensions) {
        if (extensions == null) return null;
        Object pq = extensions.get("persistedQuery");
        if (!(pq instanceof Map)) return null;
        Object hash = ((Map<String, Object>) pq).get("sha256Hash");
        return hash == null ? null : hash.toString();
    }

    /** Standard Apollo APQ miss envelope — the client retries with the full query. */
    private static Map<String, Object> persistedQueryNotFound() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", "PersistedQueryNotFound");
        err.put("extensions", Map.of("code", "PERSISTED_QUERY_NOT_FOUND"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", null);
        envelope.put("errors", java.util.List.of(err));
        return envelope;
    }

    /** Hash was supplied AND a query was supplied but the hash didn't verify — refuse. */
    private static Map<String, Object> persistedQueryMismatch() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", "provided persistedQuery hash does not match SHA-256(query)");
        err.put("extensions", Map.of("code", "PERSISTED_QUERY_HASH_MISMATCH"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", null);
        envelope.put("errors", java.util.List.of(err));
        return envelope;
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

    private static GraphQLSchema buildSchema(
            String sdl, SaikuGraphQlFetchers fetchers, Map<String, CubeTypeGenerator> perCubeGenerators) {
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
        RuntimeWiring.Builder wiringBuilder = RuntimeWiring.newRuntimeWiring()
                .scalar(ExtendedScalars.Json)
                .type("Query", builder -> {
                    builder.dataFetcher("serverInfo", fetchers.serverInfo())
                            .dataFetcher("cubes", fetchers.cubes())
                            .dataFetcher("cube", fetchers.cube())
                            .dataFetcher("ossieModels", fetchers.ossieModels())
                            .dataFetcher("ossieModel", fetchers.ossieModel())
                            .dataFetcher("executeMdx", fetchers.executeMdx())
                            .dataFetcher("executeOssie", fetchers.executeOssie());
                    for (CubeTypeGenerator gen : perCubeGenerators.values()) {
                        builder.dataFetcher(gen.getQueryFieldName(), fetchers.executeCubeField(gen));
                    }
                    return builder;
                });
        return new SchemaGenerator().makeExecutableSchema(typeRegistry, wiringBuilder.build());
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
