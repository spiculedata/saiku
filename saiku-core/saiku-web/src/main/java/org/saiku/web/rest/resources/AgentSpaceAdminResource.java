/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.ask.AgentSpace;
import org.saiku.service.olap.ai.ask.AgentSpaceRegistry;

/**
 * saiku#1440 — admin CRUD over Agent Spaces (personas), so they can be authored in the admin panel
 * instead of by hand-editing JSON under {@code saiku-home/agent-spaces/}. Reads/writes through the
 * {@link AgentSpaceRegistry} (which rescans on write, so changes go live immediately).
 *
 * <p>Path is {@code /saiku/admin/agent-spaces} — admin-gated (@RolesAllowed + the
 * {@code /rest/saiku/admin/**} intercept), same posture as the other admin resources. Unlike the
 * public {@code /ai/spaces} catalogue (which omits the system prompt + cube allowlist so an embed
 * can't read routing decisions), this admin surface returns the FULL space for editing.
 */
@Path("/saiku/admin/agent-spaces")
@RolesAllowed("ROLE_ADMIN")
public class AgentSpaceAdminResource {

    private AgentSpaceRegistry registry;

    public void setRegistry(AgentSpaceRegistry registry) {
        this.registry = registry;
    }

    /** Full spaces for the admin editor. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<SpaceDto> list() {
        List<SpaceDto> out = new ArrayList<>();
        for (AgentSpace s : registry.list()) {
            out.add(toDto(s));
        }
        return out;
    }

    /** Parse errors (malformed JSON files) so the UI can flag them. */
    @GET
    @Path("/errors")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> errors() {
        List<Map<String, String>> out = new ArrayList<>();
        for (AgentSpaceRegistry.SpaceError e : registry.errors()) {
            out.add(Map.of("source", e.path(), "code", e.code(), "message", e.message()));
        }
        return out;
    }

    /** Create or replace a space. The path id is authoritative (the body id is ignored). */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("id") String id, SpaceDto body) {
        if (!AgentSpaceRegistry.isValidId(id)) {
            return bad("id must be kebab-case ([a-z0-9-])");
        }
        if (body == null || body.name == null || body.name.isBlank()) {
            return bad("name is required");
        }
        List<AiCubeRef> cubes = new ArrayList<>();
        if (body.cubeAllowlist != null) {
            for (CubeRefDto c : body.cubeAllowlist) {
                if (c == null || blank(c.connectionName) || blank(c.catalog) || blank(c.schema) || blank(c.cubeName)) {
                    return bad("each cubeAllowlist entry needs connectionName, catalog, schema and cubeName");
                }
                cubes.add(new AiCubeRef(c.connectionName, c.catalog, c.schema, c.cubeName));
            }
        }
        AgentSpace space;
        try {
            space = new AgentSpace(
                    id,
                    body.name,
                    body.description,
                    body.systemPrompt,
                    cubes,
                    body.skillAllowlist == null ? List.of() : body.skillAllowlist,
                    body.suggestedPrompts == null ? List.of() : body.suggestedPrompts,
                    id + ".json");
            registry.save(space);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "could not save space: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.ok(toDto(space)).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        try {
            boolean removed = registry.delete(id);
            if (!removed) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "no such space: " + id))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
            return Response.ok(Map.of("deleted", id))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "could not delete space: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    private static Response bad(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static SpaceDto toDto(AgentSpace s) {
        SpaceDto d = new SpaceDto();
        d.id = s.id();
        d.name = s.name();
        d.description = s.description();
        d.systemPrompt = s.systemPrompt();
        d.skillAllowlist = new ArrayList<>(s.skillAllowlist());
        d.suggestedPrompts = new ArrayList<>(s.suggestedPrompts());
        d.cubeAllowlist = new ArrayList<>();
        for (AiCubeRef c : s.cubeAllowlist()) {
            CubeRefDto cr = new CubeRefDto();
            cr.connectionName = c.getConnectionName();
            cr.catalog = c.getCatalog();
            cr.schema = c.getSchema();
            cr.cubeName = c.getCubeName();
            d.cubeAllowlist.add(cr);
        }
        return d;
    }

    /** Editable space shape. Public fields for Jackson. */
    public static final class SpaceDto {
        public String id;
        public String name;
        public String description;
        public String systemPrompt;
        public List<CubeRefDto> cubeAllowlist;
        public List<String> skillAllowlist;
        public List<String> suggestedPrompts;
    }

    public static final class CubeRefDto {
        public String connectionName;
        public String catalog;
        public String schema;
        public String cubeName;
    }
}
