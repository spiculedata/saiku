/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.eval.EvalResultStore;

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

    public void setStore(EvalResultStore store) {
        this.store = store;
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
