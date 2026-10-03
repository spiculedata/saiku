/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.share;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.web.rest.resources.Query2Resource;
import org.saiku.web.rest.resources.notebooks.Notebook;
import org.saiku.web.rest.resources.notebooks.NotebookCell;
import org.saiku.web.rest.resources.notebooks.NotebookCubeResolver;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The notebook counterpart of {@link ShareViewResource} (issue #1108, reusing
 * the #941 share-token mechanism per the issue's "Share" scope item). Reachable
 * only with a valid {@code ROLE_SHARE_GUEST} established by {@link
 * org.saiku.web.security.share.ShareTokenAuthFilter} — it sits under the same
 * {@code /rest/saiku/share/view/} prefix the filter already scopes, so no
 * security-rule or filter change was needed to add it.
 *
 * <p>Deliberately a sibling resource rather than a change to {@link
 * ShareViewResource}: the #941 share-token/store/filter layer (id validation,
 * atomic persistence, traversal guards, request-scoped guest identity) is
 * already generic — it pins an owner identity to a repository path string and
 * doesn't assume dashboard shape — so the only touch point needed on that
 * security-critical code was widening {@link ShareTokenResource#mint}'s
 * suffix check. Everything document-shaped (typed cell lookup, how a cell's
 * query is re-run) lives here instead, mirroring how {@code AppResource} was
 * added as a clone of {@code DashboardResource} rather than a generalisation
 * of it.
 *
 * <p>The notebook the guest may see is pinned by the token (read from the
 * Authentication details, never from client input). A cell's MDX runs under
 * the share owner's data scope via {@link SessionService#runAs} — a
 * delegation by someone who held GRANT — so the guest sees exactly what was
 * shared, but with no ability to author a query or widen the cube.
 */
@Path("/saiku/share/view/notebook")
public class NotebookShareViewResource {

    private static final Logger log = LoggerFactory.getLogger(NotebookShareViewResource.class);

    /** Notebooks are UI-owned documents (saiku#1179 posture, mirrored from
     *  NotebookResource) — unknown fields must not break this read path. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatasourceService datasourceService;
    private SessionService sessionService;
    private Query2Resource query2Resource;
    private OlapDiscoverService olapDiscoverService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    public void setQuery2Resource(Query2Resource r) {
        this.query2Resource = r;
    }

    public void setOlapDiscoverService(OlapDiscoverService s) {
        this.olapDiscoverService = s;
    }

    /** The pinned notebook, so the viewer can render its cells. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response notebook() {
        ShareGuestDetails g = guest();
        if (g == null) {
            return invalid();
        }
        Notebook nb = loadNotebook(g);
        if (nb == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "Shared notebook is no longer available"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        return harden(Response.ok(nb).type(MediaType.APPLICATION_JSON).build());
    }

    /** Run a single MDX cell's authored query under the owner's scope. The
     *  guest supplies no query body — only the cell id, which must belong to
     *  the pinned notebook. */
    @POST
    @Path("/cell/{cellId}/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cellQuery(@PathParam("cellId") String cellId) {
        ShareGuestDetails g = guest();
        if (g == null) {
            return invalid();
        }
        Notebook nb = loadNotebook(g);
        if (nb == null || nb.cells == null) {
            return invalid();
        }
        NotebookCell cell = null;
        for (NotebookCell c : nb.cells) {
            if (cellId.equals(c.id)) {
                cell = c;
                break;
            }
        }
        if (cell == null
                || !"mdx".equals(cell.type)
                || cell.mdx == null
                || cell.mdx.isBlank()
                || cell.cube == null) {
            return harden(Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", "NOT_FOUND", "error", "No such runnable cell"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        final NotebookCell runCell = cell;
        try {
            // Execute under the share owner's identity (Mondrian role + JCR
            // file ACL). The query comes from the stored notebook, never the
            // client, so the guest cannot author a query or widen the cube.
            // Resolve the cell's AiCubeRef against the live schema inside the
            // same runAs block — never bind a client-authored SaikuCube (see
            // NotebookCell's javadoc for why that's not a safe request shape).
            Response result = sessionService.runAs(g.ownerUser, g.ownerRoles, () -> {
                SaikuCube cube = NotebookCubeResolver.resolve(olapDiscoverService, runCell.cube);
                if (cube == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("status", "NOT_FOUND", "error", "Cell's cube is no longer available"))
                            .type(MediaType.APPLICATION_JSON)
                            .build();
                }
                ThinQuery tq = new ThinQuery(java.util.UUID.randomUUID().toString(), cube, runCell.mdx);
                return query2Resource.execute(tq, null);
            });
            return harden(result);
        } catch (RuntimeException e) {
            log.warn("share-view notebook cell query failed for {}", cellId, e);
            return harden(Response.serverError()
                    .entity(Map.of("status", "ERROR", "error", "Cell query failed"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    /* --------------------------- helpers ---------------------------- */

    private Notebook loadNotebook(ShareGuestDetails g) {
        // Defence-in-depth: re-assert the pinned path is a notebook at the
        // trust boundary, mirroring ShareViewResource's .saikudash re-check.
        if (g.dashboardPath == null || !g.dashboardPath.endsWith(".saikunb")) {
            return null;
        }
        // Read the notebook file as the owner — the token authorises viewing
        // exactly this one notebook, so the owner's ACL is the right scope.
        String raw;
        try {
            raw = datasourceService.getFileData(g.dashboardPath, g.ownerUser, g.ownerRoles);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Notebook.class);
        } catch (Exception e) {
            log.error("shared notebook {} is unparseable", g.dashboardPath, e);
            return null;
        }
    }

    /** The guest details the filter pinned to the request, or null if absent. */
    private ShareGuestDetails guest() {
        Authentication auth = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ShareGuestDetails) {
            return (ShareGuestDetails) auth.getDetails();
        }
        return null;
    }

    private static Response invalid() {
        return harden(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("status", "SHARE_INVALID", "error", "Share link is invalid or expired."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /** Same guest-response hardening as {@link ShareViewResource#harden} —
     *  see its javadoc for the rationale of each header. */
    private static Response harden(Response r) {
        return Response.fromResponse(r)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", "frame-ancestors 'none'")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }
}
