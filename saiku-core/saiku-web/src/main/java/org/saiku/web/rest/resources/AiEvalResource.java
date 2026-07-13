/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.eval.EvalAskAdapter;
import org.saiku.service.olap.ai.eval.EvalResultStore;
import org.saiku.service.olap.ai.eval.LiveEvalAskAdapter;
import org.saiku.service.olap.ai.eval.ScheduledEvalRunner;

/**
 * saiku#1424 (Phase 3) — admin-only read API over the persisted eval results, so an accuracy
 * dashboard can plot "is the model getting worse against this cube over time?" without shell access
 * to the H2 file.
 *
 * <p>Path is {@code /saiku/admin/ai-evals} — the {@code /saiku/admin} convention, deliberately NOT
 * under {@code /ai/} so reads of the monitor aren't themselves audited/policy-gated. {@code
 * @RolesAllowed} is enforced by Jersey's RolesAllowedDynamicFeature AND the {@code
 * /rest/saiku/admin/**} Spring Security intercept-url — defence in depth, same posture as {@link
 * AiAuditResource}.
 *
 * <p>Timestamps go on the wire as epoch millis (a plain {@code long}) rather than {@code Instant},
 * so the response doesn't depend on the JavaTimeModule being registered on the shared mapper.
 */
@Path("/saiku/admin/ai-evals")
@RolesAllowed("ROLE_ADMIN")
public class AiEvalResource {

    private EvalResultStore store;
    private AiAskService askService;
    private AiCubeMetadataService cubeMetadataService;
    private ThinQueryService thinQueryService;
    private String evalsDir = "./saiku-home/evals";

    public void setStore(EvalResultStore store) {
        this.store = store;
    }

    public void setAskService(AiAskService askService) {
        this.askService = askService;
    }

    public void setCubeMetadataService(AiCubeMetadataService cubeMetadataService) {
        this.cubeMetadataService = cubeMetadataService;
    }

    public void setThinQueryService(ThinQueryService thinQueryService) {
        this.thinQueryService = thinQueryService;
    }

    public void setEvalsDir(String evalsDir) {
        if (evalsDir != null && !evalsDir.isBlank()) {
            this.evalsDir = evalsDir;
        }
    }

    /**
     * Run every suite in the evals directory against the live model and persist each report, then
     * return the sweep summary. Executes <em>synchronously in the request thread</em> — this is the
     * whole point of the out-of-process CLI design (saiku#1478): the sweep runs inside a real
     * request, so the session-scoped {@code thinQueryService} resolves and queries execute under the
     * caller's (admin's) data scope. A background cron thread couldn't do this.
     *
     * <p>The {@code saiku eval} CLI POSTs here from a separate process and reads the tallies to set
     * its exit code, so CI can gate on accuracy regressions without an in-server scheduler.
     */
    @POST
    @Path("/run")
    @Produces(MediaType.APPLICATION_JSON)
    public Response run() {
        if (askService == null || cubeMetadataService == null || thinQueryService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "eval execution is not configured (missing ask/metadata/query service)"))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        AiSchemaConverter converter = new AiSchemaConverter();
        EvalAskAdapter adapter = new LiveEvalAskAdapter(askService, cubeMetadataService, converter, thinQueryService);
        ScheduledEvalRunner runner = new ScheduledEvalRunner(java.nio.file.Path.of(evalsDir), adapter, store);
        ScheduledEvalRunner.SweepSummary summary = runner.runAll();

        List<Map<String, Object>> results = new ArrayList<>();
        int totalCases = 0;
        int passedCases = 0;
        for (ScheduledEvalRunner.SuiteResult r : summary.results()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("suiteName", r.suiteName());
            m.put("runId", r.runId());
            m.put("passed", r.passed());
            m.put("total", r.total());
            m.put("oneLine", r.oneLine());
            results.add(m);
            totalCases += r.total();
            passedCases += r.passed();
        }
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (ScheduledEvalRunner.SkippedSuite s : summary.skipped()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("suiteName", s.suiteName());
            m.put("reason", s.reason());
            skipped.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suites", summary.results().size());
        out.put("total", totalCases);
        out.put("passed", passedCases);
        out.put("results", results);
        out.put("skipped", skipped);
        // The CLI keys its exit code off this: any case failure OR any skipped (unrunnable) suite.
        out.put("hasFailures", passedCases < totalCases || !summary.skipped().isEmpty());
        out.put("summary", summary.oneLine());
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build();
    }

    /** One card per suite that has ever run — the suite name plus its latest run's tally. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<SuiteCard> suites() {
        List<SuiteCard> cards = new ArrayList<>();
        for (String name : store.suites()) {
            cards.add(new SuiteCard(name, toDto(store.latestRun(name))));
        }
        return cards;
    }

    /** Recent runs for a suite, newest-first, for a runs table. */
    @GET
    @Path("/{suite}/runs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RunDto> runs(@PathParam("suite") String suite, @QueryParam("limit") @DefaultValue("50") int limit) {
        List<RunDto> out = new ArrayList<>();
        for (EvalResultStore.RunSummary r : store.recentRuns(suite, limit)) {
            out.add(toDto(r));
        }
        return out;
    }

    /** Pass-rate over time for a suite, oldest-first, for a trend chart. */
    @GET
    @Path("/{suite}/trend")
    @Produces(MediaType.APPLICATION_JSON)
    public List<TrendDto> trend(@PathParam("suite") String suite, @QueryParam("limit") @DefaultValue("100") int limit) {
        List<TrendDto> out = new ArrayList<>();
        for (EvalResultStore.TrendPoint p : store.passRateSeries(suite, limit)) {
            out.add(new TrendDto(p.startedAt() == null ? 0L : p.startedAt().toEpochMilli(), p.passRate(), p.total()));
        }
        return out;
    }

    private static RunDto toDto(EvalResultStore.RunSummary r) {
        if (r == null) {
            return null;
        }
        return new RunDto(
                r.runId(),
                r.suiteName(),
                r.cubeRef(),
                r.startedAt() == null ? 0L : r.startedAt().toEpochMilli(),
                r.elapsedMs(),
                r.total(),
                r.passed(),
                r.failed(),
                r.degraded(),
                r.skipped(),
                r.passRate());
    }

    /** Suite list card. Public fields for Jackson. */
    public record SuiteCard(String name, RunDto latest) {}

    /** One persisted run, timestamp as epoch millis. */
    public record RunDto(
            long runId,
            String suiteName,
            String cubeRef,
            long startedAt,
            long elapsedMs,
            int total,
            int passed,
            int failed,
            int degraded,
            int skipped,
            double passRate) {}

    /** One trend point, timestamp as epoch millis. */
    public record TrendDto(long startedAt, double passRate, int total) {}
}
