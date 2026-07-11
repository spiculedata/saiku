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
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraphQL over HTTP for Saiku.
 *
 * <p>Two shapes accepted:
 * <ul>
 *   <li><b>POST /rest/saiku/api/graphql</b> — body {@code {query, variables?, operationName?}}.
 *       The standard GraphQL-over-HTTP surface. Introspection queries work out of the box.</li>
 *   <li><b>GET /rest/saiku/api/graphql?query=...&variables=...&operationName=...</b> —
 *       shareable, for queries an operator wants to bookmark. Variables come URL-encoded.</li>
 * </ul>
 *
 * <p>A third endpoint returns the raw SDL:
 * <ul>
 *   <li><b>GET /rest/saiku/api/graphql/schema.graphql</b> — text/plain, cache-friendly.</li>
 * </ul>
 *
 * <p>Auth is inherited from Spring Security via the JAX-RS Application config — any credential
 * a REST endpoint accepts (session cookie, JWT) works here too.
 */
@Path("/saiku/api/graphql")
@PermitAll
public class GraphQlResource {

    private static final Logger log = LoggerFactory.getLogger(GraphQlResource.class);

    private SaikuGraphQlService graphQlService;
    private ObjectMapper mapper = new ObjectMapper();

    public void setGraphQlService(SaikuGraphQlService s) {
        this.graphQlService = s;
    }

    public void setMapper(ObjectMapper m) {
        this.mapper = m;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response execute(Map<String, Object> body) {
        if (graphQlService == null) {
            return unavailable("GraphQL engine not wired");
        }
        String query = body != null ? asString(body.get("query")) : null;
        String operationName = body != null ? asString(body.get("operationName")) : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = body != null && body.get("variables") instanceof Map
                ? (Map<String, Object>) body.get("variables")
                : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = body != null && body.get("extensions") instanceof Map
                ? (Map<String, Object>) body.get("extensions")
                : null;
        Map<String, Object> result = graphQlService.execute(query, variables, operationName, extensions);
        return Response.ok(result).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeGet(
            @QueryParam("query") String query,
            @QueryParam("variables") String variablesJson,
            @QueryParam("operationName") String operationName,
            @QueryParam("extensions") String extensionsJson) {
        if (graphQlService == null) {
            return unavailable("GraphQL engine not wired");
        }
        Map<String, Object> variables = null;
        if (variablesJson != null && !variablesJson.isBlank()) {
            try {
                variables = mapper.readValue(variablesJson, Map.class);
            } catch (Exception e) {
                return badRequest("variables must be a JSON object: " + e.getMessage());
            }
        }
        Map<String, Object> extensions = null;
        if (extensionsJson != null && !extensionsJson.isBlank()) {
            try {
                extensions = mapper.readValue(extensionsJson, Map.class);
            } catch (Exception e) {
                return badRequest("extensions must be a JSON object: " + e.getMessage());
            }
        }
        Map<String, Object> result = graphQlService.execute(query, variables, operationName, extensions);
        return Response.ok(result).build();
    }

    /**
     * Admin-only: force a schema rebuild against the current cube estate. Called after
     * {@code POST /admin/discover/refresh} in operator scripts so the GraphQL type surface
     * picks up cubes added since boot. Persisted query cache is wiped as a side-effect since
     * the schema shape may have changed.
     */
    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.annotation.security.RolesAllowed({"ROLE_ADMIN"})
    public Response refresh() {
        if (graphQlService == null) {
            return unavailable("GraphQL engine not wired");
        }
        graphQlService.refresh();
        return Response.ok(Map.of("status", "ok")).build();
    }

    /**
     * Returns the raw SDL for the running schema so operators + codegen tools can grab it
     * without an introspection query. Static within a process lifecycle — a container-level
     * ETag cache is safe.
     */
    @GET
    @Path("/schema.graphql")
    @Produces(MediaType.TEXT_PLAIN)
    public Response schema() {
        if (graphQlService == null) {
            return unavailable("GraphQL engine not wired");
        }
        return Response.ok(graphQlService.getSdl()).type(MediaType.TEXT_PLAIN).build();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Response unavailable(String message) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("errors", List.of(Map.of("message", message))))
                .build();
    }

    private static Response badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", List.of(Map.of("message", message)));
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
