/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.saiku.service.mcp.outbound.McpOutboundClient;
import org.saiku.service.mcp.outbound.McpOutboundServer;
import org.saiku.service.mcp.outbound.McpOutboundServerRegistry;
import org.saiku.service.mcp.outbound.McpOutboundToolCatalog;

/**
 * saiku#1425 — admin CRUD over outbound MCP server registrations, so they can be authored in the
 * admin panel instead of by hand-editing JSON under {@code saiku-home/mcp-servers/}. Reads/writes
 * through {@link McpOutboundServerRegistry} (rescans on write) and drives live discovery through
 * {@link McpOutboundClient} so the admin picks which discovered tools to enable — the default-off
 * allowlist model described on {@link McpOutboundServer}.
 *
 * <p>Path is {@code /saiku/admin/mcp-servers} — admin-gated, same posture as {@code
 * AgentSpaceAdminResource}. Credentials are WRITE-ONLY through this surface: {@link
 * McpOutboundServer#authHeaderValue()} is never echoed back in a list/get response (only whether
 * one is set, via {@link ServerDto#hasAuthHeader}); a {@code PUT} with a {@code null}
 * {@code authHeaderValue} keeps the server's existing credential unchanged, an explicit empty
 * string clears it — the same "blank means unchanged" convention as editing a saved password.
 */
@Path("/saiku/admin/mcp-servers")
@RolesAllowed("ROLE_ADMIN")
public class McpOutboundAdminResource {

    private McpOutboundServerRegistry registry;
    private McpOutboundClient client;
    private McpOutboundToolCatalog catalog;

    public void setRegistry(McpOutboundServerRegistry registry) {
        this.registry = registry;
    }

    public void setClient(McpOutboundClient client) {
        this.client = client;
    }

    public void setCatalog(McpOutboundToolCatalog catalog) {
        this.catalog = catalog;
    }

    /** Registered servers, credentials omitted. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ServerDto> list() {
        List<ServerDto> out = new ArrayList<>();
        for (McpOutboundServer s : registry.list()) {
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
        for (McpOutboundServerRegistry.ServerError e : registry.errors()) {
            out.add(Map.of("source", e.path(), "code", e.code(), "message", e.message()));
        }
        return out;
    }

    /**
     * Live {@code tools/list} against one registered server — the admin picks from THIS result which
     * tool names go into {@link ServerDto#enabledTools}. Distinct from the ask-layer catalogue (which
     * only ever surfaces already-enabled tools): this endpoint is discovery-before-enablement.
     */
    @GET
    @Path("/{id}/tools")
    @Produces(MediaType.APPLICATION_JSON)
    public Response discoverTools(@PathParam("id") String id) {
        Optional<McpOutboundServer> server = registry.get(id);
        if (server.isEmpty()) {
            return notFound(id);
        }
        McpOutboundClient.DiscoveryResult result = client.discoverTools(server.get());
        if (!result.ok()) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("status", "UNREACHABLE", "error", result.error()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpOutboundClient.RemoteTool t : result.tools()) {
            tools.add(Map.of(
                    "name", t.name(),
                    "description", t.description(),
                    "enabled", server.get().isToolEnabled(t.name())));
        }
        return Response.ok(Map.of("tools", tools))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** Create or replace a server registration. The path id is authoritative. */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("id") String id, ServerDto body) {
        if (!McpOutboundServerRegistry.isValidId(id)) {
            return bad("id must be kebab-case ([a-z0-9-])");
        }
        if (body == null || body.name == null || body.name.isBlank()) {
            return bad("name is required");
        }
        if (body.url == null || body.url.isBlank()) {
            return bad("url is required");
        }

        // "blank means unchanged" for the credential — see the class javadoc. A brand-new server
        // (no prior registration) has nothing to inherit, so a null authHeaderValue there just means
        // "no credential", same as an explicit clear.
        Optional<McpOutboundServer> existing = registry.get(id);
        String authHeaderName = body.authHeaderName;
        String authHeaderValue = body.authHeaderValue;
        if (authHeaderValue == null && existing.isPresent() && existing.get().hasAuthHeader()) {
            authHeaderName = existing.get().authHeaderName();
            authHeaderValue = existing.get().authHeaderValue();
        } else if (authHeaderValue != null && authHeaderValue.isBlank()) {
            authHeaderName = null;
            authHeaderValue = null;
        }
        if ((authHeaderName == null || authHeaderName.isBlank()) != (authHeaderValue == null)) {
            return bad("authHeaderName and authHeaderValue must be set together, or both omitted");
        }

        Set<String> enabledTools = body.enabledTools == null ? Set.of() : new LinkedHashSet<>(body.enabledTools);

        McpOutboundServer server;
        try {
            server = new McpOutboundServer(
                    id, body.name, body.url, authHeaderName, authHeaderValue, enabledTools, id + ".json");
            registry.save(server);
            if (catalog != null) {
                catalog.forceRefresh();
            }
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "could not save server: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.ok(toDto(server)).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        try {
            boolean removed = registry.delete(id);
            if (!removed) {
                return notFound(id);
            }
            if (catalog != null) {
                catalog.forceRefresh();
            }
            return Response.ok(Map.of("deleted", id))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity(Map.of("error", "could not delete server: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /** Force a rescan of the registry AND the ask-layer catalogue cache. */
    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refresh() {
        registry.forceRefresh();
        if (catalog != null) {
            catalog.forceRefresh();
        }
        return Response.ok(Map.of("refreshed", true))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response notFound(String id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "no such server: " + id))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response bad(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("status", "VALIDATION_ERROR", "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static ServerDto toDto(McpOutboundServer s) {
        ServerDto d = new ServerDto();
        d.id = s.id();
        d.name = s.name();
        d.url = s.url();
        d.hasAuthHeader = s.hasAuthHeader();
        d.authHeaderName = s.hasAuthHeader() ? s.authHeaderName() : null;
        d.enabledTools = new ArrayList<>(s.enabledTools());
        return d;
    }

    /**
     * Editable server shape. Public fields for Jackson. {@code authHeaderValue} is write-only — never
     * populated on a response; see {@link #hasAuthHeader} instead to know whether a credential is
     * set.
     */
    public static final class ServerDto {
        public String id;
        public String name;
        public String url;
        public String authHeaderName;
        public String authHeaderValue;
        public boolean hasAuthHeader;
        public List<String> enabledTools;
    }
}
