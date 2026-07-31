/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.apps;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.apps.TilePluginManifest;
import org.saiku.service.apps.TilePluginParser;
import org.saiku.service.apps.TilePluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only REST surface over the admin-installed tile plugin catalogue (App Builder Phase 2,
 * saiku#1441). Serves the trusted plugin HTML the dashboard iframe uses as its {@code srcdoc}.
 *
 * <p>Mounted at {@code /saiku/api/tile-plugins} — full-auth (inherits the {@code /rest/**}
 * {@code isFullyAuthenticated()} intercept). Dashboard authoring is already full-auth, and authors
 * only <em>pick</em> a plugin; the HTML itself is admin-installed (dropped into
 * {@code saiku-home/tile-plugins/} by an operator), never author-supplied, so no admin role is
 * required to read the catalogue.
 *
 * <p>The registry is the single source of truth: it validates plugin ids to a safe slug and reads
 * html only for a catalogued id, so the {@code {id}} path param can never traverse the filesystem
 * (see {@link TilePluginRegistry#html(String)}). This resource additionally rejects a
 * slug-invalid id with 404 before touching the registry.
 */
@Path("/saiku/api/tile-plugins")
public class TilePluginResource {

    private static final Logger log = LoggerFactory.getLogger(TilePluginResource.class);

    private TilePluginRegistry registry;

    public void setRegistry(TilePluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * List installed tile plugins. When {@code errors=true}, returns the parse errors from the last
     * scan instead (id/dir, stable code, message) so an admin UI can flag broken bundles.
     *
     * @return JSON array of {@code {id, label, optionSchema?}} manifests, or {@code {path, code,
     *     message}} error entries when {@code errors=true}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("errors") @DefaultValue("false") boolean errors) {
        if (registry == null) {
            return Response.ok(List.of()).build();
        }
        if (errors) {
            List<Map<String, String>> out = new ArrayList<>();
            for (TilePluginRegistry.PluginError e : registry.errors()) {
                out.add(Map.of("path", e.path(), "code", e.code(), "message", e.message()));
            }
            return Response.ok(out).build();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TilePluginManifest m : registry.list()) {
            out.add(m.asSummary());
        }
        return Response.ok(out).build();
    }

    /**
     * Return the {@code plugin.html} srcdoc source for {@code id} as {@code text/html}, or 404 if
     * there is no such installed plugin. This is the exact markup the App Builder loads into a
     * sandboxed iframe's {@code srcdoc} under a strict CSP.
     *
     * <p>{@code id} is validated to the slug rule before the registry is touched; the registry itself
     * only returns html for a catalogued, in-root plugin — so a hostile id (dots, slashes, {@code
     * ..}) is rejected at the door and can never escape {@code saiku-home/tile-plugins/}.
     */
    @GET
    @Path("/{id}/html")
    @Produces(MediaType.TEXT_HTML)
    public Response html(@PathParam("id") String id) {
        if (registry == null || !TilePluginParser.isValidId(id)) {
            return notFound(id);
        }
        String html = registry.html(id);
        if (html == null) {
            return notFound(id);
        }
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    private Response notFound(String id) {
        log.debug("tile plugin html requested for unknown/invalid id '{}'", id);
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", "NOT_FOUND", "error", "No such tile plugin"))
                .build();
    }
}
