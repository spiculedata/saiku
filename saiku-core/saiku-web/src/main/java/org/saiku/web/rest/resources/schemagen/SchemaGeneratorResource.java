/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.SuggestionSet;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.session.SchemaGenSession;
import org.saiku.service.schema.generate.session.SchemaGenSessionStore;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-scoped REST endpoints driving the schema-generation workflow.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@code POST /start/{dataSourceId}} — kicks off an orchestrator run, returns 202 with a
 *       session id.
 *   <li>{@code GET /{sessionId}/status} — poll target; returns current {@link
 *       SchemaGenSession.Stage} plus counts.
 *   <li>{@code GET /{sessionId}/draft} — the current draft tree (after {@code READY}).
 *   <li>{@code GET /{sessionId}/suggestions} — LLM / rule suggestion set.
 *   <li>{@code POST /{sessionId}/ops} — apply a single {@code SuggestionOp} and return the updated
 *       draft.
 *   <li>{@code POST /{sessionId}/save} — serialise with {@link MondrianSchemaWriter}, hand the XML
 *       (+ sidecar) to a {@link SchemaSink} and flip the session to {@code SAVED}.
 * </ol>
 *
 * <p>Wiring is intentionally thin: the resource is constructed with collaborators and has no
 * Spring/Guice annotations. The {@link SchemaSink} abstracts the repository write so
 * {@link DatasourceService} can be bound in C4 without changing this class. A
 * {@link #datasourceBackedSink(DatasourceService)} factory is provided for production wiring.
 *
 * <p>Auth: admin resources in this codebase use an inline {@code userService.isAdmin()} check
 * (see {@link org.saiku.web.rest.resources.AdminResource}). We follow the same pattern —
 * {@code userService} is optional to keep the constructor test-friendly; production wiring sets it
 * and the guard short-circuits non-admins with 403. When {@code userService} is {@code null}, the
 * guard is skipped (test/headless mode).
 */
@Path("/saiku/admin/schema-generator")
public class SchemaGeneratorResource {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaGeneratorResource.class);

    /**
     * Abstracts persistence of a generated schema XML + sidecar JSON. A thin interface keeps the
     * resource decoupled from {@link DatasourceService} / {@code IRepositoryManager} so tests can
     * record writes without booting Saiku's repository layer.
     */
    public interface SchemaSink {
        /**
         * Persist the generated schema. {@code sidecarJson} is the companion
         * {@code <name>.generated.json} payload; may be {@code null} if the sidecar is deferred.
         */
        void writeSchema(String dataSourceId, String name, String xml, String sidecarJson) throws Exception;
    }

    private final SchemaGenSessionStore store;
    private final SchemaGenOrchestrator orchestrator;
    private final OpApplier opApplier;
    private final MondrianSchemaWriter writer;
    private final SchemaSink sink;
    private UserService userService; // optional; see class doc

    public SchemaGeneratorResource(
            SchemaGenSessionStore store,
            SchemaGenOrchestrator orchestrator,
            OpApplier opApplier,
            MondrianSchemaWriter writer,
            SchemaSink sink) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.opApplier = opApplier;
        this.writer = writer;
        this.sink = sink;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    // -- endpoints ------------------------------------------------------------

    /**
     * Kick off a schema-gen pipeline for {@code dataSourceId}. Returns 202 + {@link StartResponse}
     * with the new session id.
     */
    @POST
    @Path("/start/{dataSourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response start(@PathParam("dataSourceId") String dataSourceId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        SchemaGenSession session = orchestrator.start(dataSourceId);
        return Response.status(Response.Status.ACCEPTED)
                .entity(new StartResponse(
                        session.id(), dataSourceId, session.stage().name()))
                .build();
    }

    /** Poll target: current stage, failure message, and progress counts. */
    @GET
    @Path("/{sessionId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("sessionId") String sessionId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Optional<SchemaGenSession> opt = store.get(sessionId);
        if (opt.isEmpty()) {
            return notFound(sessionId);
        }
        SchemaGenSession s = opt.get();
        int cubeCount = s.draft() == null ? 0 : s.draft().cubes().size();
        int suggestionCount =
                s.suggestions() == null ? 0 : s.suggestions().ops().size();
        DeltaReport delta = s.deltaReport();
        int deltaNewCount =
                delta == null || delta.newPaths() == null ? 0 : delta.newPaths().size();
        int deltaRemovedCount = delta == null || delta.removedUpstreamPaths() == null
                ? 0
                : delta.removedUpstreamPaths().size();
        return Response.ok(new StatusResponse(
                        s.id(),
                        s.stage().name(),
                        s.failureMessage(),
                        cubeCount,
                        suggestionCount,
                        deltaNewCount,
                        deltaRemovedCount))
                .build();
    }

    /** Current draft tree. 404 if session missing/expired. */
    @GET
    @Path("/{sessionId}/draft")
    @Produces(MediaType.APPLICATION_JSON)
    public Response draft(@PathParam("sessionId") String sessionId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Optional<SchemaGenSession> opt = store.get(sessionId);
        if (opt.isEmpty()) {
            return notFound(sessionId);
        }
        DraftSchema draft = opt.get().draft();
        if (draft == null) {
            // Pipeline hasn't finished inferring — client should poll /status first. Still return
            // an empty view with the known name so the UI can render a skeleton.
            return Response.ok(new DraftView(null, java.util.List.of(), java.util.List.of()))
                    .build();
        }
        return Response.ok(DraftView.from(draft)).build();
    }

    /** Aggregate of suggestions emitted by the enrichment layer. */
    @GET
    @Path("/{sessionId}/suggestions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response suggestions(@PathParam("sessionId") String sessionId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Optional<SchemaGenSession> opt = store.get(sessionId);
        if (opt.isEmpty()) {
            return notFound(sessionId);
        }
        SuggestionSet s = opt.get().suggestions();
        // Normalise null → empty so the client always sees a SuggestionSet shape.
        return Response.ok(new SuggestionView(s == null ? new SuggestionSet() : s))
                .build();
    }

    /**
     * Apply {@code OpRequest.op} in place against the session's draft and return the updated tree.
     * Path-resolution failures inside {@link OpApplier} surface as 400.
     */
    @POST
    @Path("/{sessionId}/ops")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response applyOp(@PathParam("sessionId") String sessionId, OpRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (req == null || req.op() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("missing op in request body")
                    .build();
        }
        Optional<SchemaGenSession> opt = store.get(sessionId);
        if (opt.isEmpty()) {
            return notFound(sessionId);
        }
        SchemaGenSession s = opt.get();
        DraftSchema draft = s.draft();
        if (draft == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("draft not yet available — poll /status first")
                    .build();
        }
        try {
            opApplier.apply(draft, req.op());
            s.appendOp(req.op());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(e.getMessage())
                    .build();
        }
        return Response.ok(DraftView.from(draft)).build();
    }

    /**
     * Serialise the draft via {@link MondrianSchemaWriter}, hand the XML (+ sidecar JSON) to
     * {@link SchemaSink}, flip the session to {@code SAVED}, return 204.
     *
     * <p>The sidecar payload is a minimal placeholder in C3 — enough to exercise the write path.
     * The full provenance-bearing sidecar is a C4/E1 task (see TODO).
     */
    @POST
    @Path("/{sessionId}/save")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("sessionId") String sessionId, SaveRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Optional<SchemaGenSession> opt = store.get(sessionId);
        if (opt.isEmpty()) {
            return notFound(sessionId);
        }
        SchemaGenSession s = opt.get();
        DraftSchema draft = s.draft();
        if (draft == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("draft not yet available — nothing to save")
                    .build();
        }
        if (req != null && req.schemaName() != null && !req.schemaName().isEmpty()) {
            draft.setName(req.schemaName());
        }
        String xml;
        String sidecar;
        try {
            MondrianSchemaWriter.WriteResult written = writer.writeWithSidecar(draft, s.opLog());
            xml = written.xml();
            sidecar = written.sidecarJson();
        } catch (Exception e) {
            LOG.warn("Failed to serialise draft for session {}: {}", sessionId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("failed to serialise draft: " + e.getMessage())
                    .build();
        }
        String name = draft.name() == null ? "generated" : draft.name();
        try {
            sink.writeSchema(s.dataSourceId(), name, xml, sidecar);
        } catch (Exception e) {
            LOG.warn("SchemaSink refused write for session {}: {}", sessionId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("failed to persist schema: " + e.getMessage())
                    .build();
        }
        s.setStage(SchemaGenSession.Stage.SAVED);
        return Response.noContent().build();
    }

    // -- helpers --------------------------------------------------------------

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    private static Response notFound(String sessionId) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("no session for id " + sessionId)
                .build();
    }

    /**
     * Default production {@link SchemaSink} backed by {@link DatasourceService}. Placed here so
     * wiring (C4) can simply call {@code new SchemaGeneratorResource(..., schemaSink)}.
     *
     * <p>Writes the XML to {@code /datasources/&lt;name&gt;.xml} (matching the path convention
     * used by {@code AdminResource.uploadSchema}) and the sidecar JSON to
     * {@code /datasources/&lt;name&gt;.generated.json} alongside it. The sidecar persists the draft
     * tree plus applied op log so later re-runs can compute a delta and restrict enrichment to new
     * elements only.
     */
    public static SchemaSink datasourceBackedSink(DatasourceService datasourceService) {
        return (dataSourceId, name, xml, sidecarJson) -> {
            String xmlPath = "/datasources/" + name + ".xml";
            datasourceService.addSchema(xml, xmlPath, name);
            if (sidecarJson != null && !sidecarJson.isEmpty()) {
                String sidecarPath = "/datasources/" + name + ".generated.json";
                String status = datasourceService.saveInternalFile(sidecarPath, sidecarJson, "nt:saikufiles");
                LOG.info(
                        "Schema {} saved; sidecar ({} bytes) persisted to {} — {}",
                        name,
                        sidecarJson.length(),
                        sidecarPath,
                        status);
            }
        };
    }
}
