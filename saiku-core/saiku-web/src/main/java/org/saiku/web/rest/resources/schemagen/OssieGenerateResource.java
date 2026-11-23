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
import java.util.List;
import java.util.Optional;
import org.saiku.service.ossie.generate.OssieGenerationJob;
import org.saiku.service.ossie.generate.OssieGenerationJobStore;
import org.saiku.service.ossie.generate.OssieModelGenerationService;
import org.saiku.service.schema.generate.delta.DeltaReport;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST surface for one-click Ossie model generation (saiku#1439).
 *
 * <pre>
 *   POST /saiku/admin/ossie-generate                  → 202 { jobId, stage }
 *   GET  /saiku/admin/ossie-generate/{jobId}          → stage + counts + artefact paths
 * </pre>
 *
 * <p><b>Why {@code /saiku/admin/} and not the issue's {@code /saiku/api/ossie/generate}.</b> The
 * Spring Security chain in {@code applicationContext-saiku.xml} gates {@code
 * /rest/saiku/admin/**} at {@code hasRole('ADMIN')} but leaves {@code /rest/saiku/api/**} at
 * {@code isFullyAuthenticated()} — any logged-in analyst. Generation reads warehouse credentials
 * and writes into the repository, so it belongs behind the role gate, alongside its sibling {@link
 * SchemaGeneratorResource} which already generates schemas under {@code /saiku/admin/}. The inline
 * {@link #adminGuard()} below stays as a second, code-level check.
 *
 * <p>{@code POST} returns 202 with a job id because a 47-table warehouse takes 30–60s: the
 * introspect and enrich stages are dominated by LLM round-trips, and holding an HTTP request open
 * across them is how the cube-designer's generate button started timing out. Clients poll the
 * {@code GET}.
 *
 * <p>Admin-scoped, following {@link SchemaGeneratorResource}'s guard: an inline {@code
 * userService.isAdmin()} check returning 403, with {@code userService} optional so unit tests and
 * headless wiring don't need the security layer. When it's {@code null} the guard is skipped —
 * that mirrors the sibling resource exactly, and is why the production bean must set it.
 *
 * <p>Wiring is by constructor, no Spring annotations on the class. The bean lives in {@code
 * saiku-beans.xml} next to the schema-generator graph and reuses its introspector, inferrer,
 * enricher, op-applier and connection provider.
 */
@Path("/saiku/admin/ossie-generate")
public class OssieGenerateResource {

    private static final Logger LOG = LoggerFactory.getLogger(OssieGenerateResource.class);

    private final OssieModelGenerationService generationService;
    private final OssieGenerationJobStore jobStore;
    private UserService userService; // optional; see class doc

    public OssieGenerateResource(OssieModelGenerationService generationService, OssieGenerationJobStore jobStore) {
        this.generationService = generationService;
        this.jobStore = jobStore;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Kick off a generation run. Returns 202 + the new job id; poll the {@code GET} for progress.
     *
     * <p>Body is optional — an empty body generates under the data-source's own name. A {@code
     * modelName} is accepted so an operator can keep two generations of the same warehouse apart
     * without renaming the data source.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response start(GenerateRequest req) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (req == null || req.dataSourceId() == null || req.dataSourceId().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("dataSourceId is required")
                    .build();
        }
        OssieGenerationJob job = generationService.start(req.dataSourceId(), req.modelName());
        LOG.info("Ossie model generation requested for '{}' as job {}", req.dataSourceId(), job.id());
        return Response.accepted(new GenerateStartResponse(
                        job.id(),
                        job.dataSourceId(),
                        job.modelName(),
                        job.stage().name()))
                .build();
    }

    /** Poll target: current stage, failure reason, artefact paths, and delta counts. */
    @GET
    @Path("/{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("jobId") String jobId) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        Optional<OssieGenerationJob> found = jobStore.get(jobId);
        if (found.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("no generation job for id " + jobId)
                    .build();
        }
        OssieGenerationJob job = found.get();
        DeltaReport delta = job.deltaReport();
        return Response.ok(new GenerateStatusResponse(
                        job.id(),
                        job.dataSourceId(),
                        job.modelName(),
                        job.stage().name(),
                        job.failureMessage(),
                        job.yamlPath(),
                        job.rationalePath(),
                        job.semanticModelCount(),
                        job.datasetCount(),
                        job.fieldCount(),
                        job.metricCount(),
                        job.relationshipCount(),
                        job.appliedOps().size(),
                        job.skippedOps().size(),
                        job.degraded(),
                        job.skippedCubes(),
                        delta == null ? 0 : delta.newPaths().size(),
                        delta == null ? 0 : delta.existingPaths().size(),
                        delta == null ? 0 : delta.removedUpstreamPaths().size()))
                .build();
    }

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    /** Request body. {@code modelName} is optional and defaults to {@code dataSourceId}. */
    public record GenerateRequest(String dataSourceId, String modelName) {}

    /** 202 body: everything a client needs to start polling, and nothing it doesn't. */
    public record GenerateStartResponse(String jobId, String dataSourceId, String modelName, String stage) {}

    /** Poll body. Delta counts are 0 on a first run (no baseline to diff against). */
    public record GenerateStatusResponse(
            String jobId,
            String dataSourceId,
            String modelName,
            String stage,
            String failureMessage,
            String yamlPath,
            String rationalePath,
            int semanticModelCount,
            int datasetCount,
            int fieldCount,
            int metricCount,
            int relationshipCount,
            int appliedSuggestionCount,
            int rejectedSuggestionCount,
            boolean degraded,
            List<String> skippedCubes,
            int newElementCount,
            int unchangedElementCount,
            int removedUpstreamElementCount) {}
}
