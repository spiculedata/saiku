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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.saiku.service.schedule.JobScheduler;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.ScheduledJobView;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin CRUD over periodic-send jobs (saiku#1809 PR4) at {@code /saiku/admin/jobs}. Lets an admin
 * list, create, enable/disable, delete, and manually fire the scheduled jobs the live {@link
 * JobScheduler} ticker runs.
 *
 * <p><b>No mail is sent.</b> PR4 wires the engine live and registers only the no-op handler — there is
 * NO real send handler in the app, so neither the ticker nor a manual {@code run} can send anything.
 * This endpoint is admin plumbing for a delivery capability that lands in a later slice.
 *
 * <p><b>Security (mirrors {@link org.saiku.web.email.MailConfigResource}).</b>
 *
 * <ul>
 *   <li>Admin-only: class-level {@code @RolesAllowed("ROLE_ADMIN")} (the JAX-RS gate) AND an explicit
 *       {@code userService.isAdmin()} check per method. There is also the Spring URL gate over {@code
 *       /rest/saiku/admin/**}.
 *   <li>Read-safe view: every response is a redacted {@link ScheduledJobView} (payload VALUES omitted,
 *       key names only) — NEVER the on-disk {@link ScheduledJobFile}, which carries the opaque payload
 *       blob.
 *   <li>Server-minted owner: a created job is owned by the CALLING ADMIN — the owner username + roles
 *       snapshot come off the request's SecurityContext, never off the request body. There is no code
 *       path that lets a client set the owner (no privilege-escalation via a forged owner).
 *   <li>Fire-now is rate-limited per admin (a manual run reaches the same worker pool as the ticker).
 * </ul>
 */
@Path("/saiku/admin/jobs")
@RolesAllowed("ROLE_ADMIN")
public class SchedulerResource {

    private static final Logger log = LoggerFactory.getLogger(SchedulerResource.class);

    private JobStore jobStore;
    private JobScheduler jobScheduler;
    private UserService userService;

    /**
     * Per-admin rate limit for the fire-now endpoint. A manual run submits to the same worker pool as
     * the ticker, so an unbounded frequency is an abuse/DoS vector even behind the admin gate — cap it
     * low (default 10/min). Mirrors {@code MailConfigResource}'s test-send limiter.
     */
    private AiRateLimiter runNowRateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.jobs.run.ratelimit.maxPerMinute", 10), 60_000L);

    public void setJobStore(JobStore jobStore) {
        this.jobStore = jobStore;
    }

    public void setJobScheduler(JobScheduler jobScheduler) {
        this.jobScheduler = jobScheduler;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** Spring/test setter to override the per-admin fire-now rate limit. */
    public void setRunNowRateLimiter(AiRateLimiter runNowRateLimiter) {
        this.runNowRateLimiter = runNowRateLimiter;
    }

    /** List all jobs as redacted views (payload values omitted). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        if (!isAdmin()) {
            return forbidden();
        }
        List<ScheduledJobView> views =
                jobStore.listAll().stream().map(ScheduledJobView::of).collect(Collectors.toList());
        return Response.ok(views).build();
    }

    /**
     * Create a job owned by the calling admin. Owner username + roles snapshot are server-minted from
     * the request SecurityContext — never from the body.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(ScheduledJobRequest body) {
        if (!isAdmin()) {
            return forbidden();
        }
        if (body == null) {
            return badRequest("request body is required");
        }
        if (body.getType() == null || body.getType().isBlank()) {
            return badRequest("type is required");
        }
        String owner = userService.getActiveUsername();
        if (owner == null || owner.isBlank()) {
            // No resolvable identity — refuse to create an owner-less job (fail-closed).
            return forbidden();
        }
        List<String> ownerRoles = currentRoles();

        ScheduledJobFile created = jobStore.create(
                owner, ownerRoles, body.getSchedule(), body.getType(), body.getPayload(), body.isEnabled());
        log.info(
                "Admin {} created job {} (type={}, enabled={})",
                owner,
                created.getId(),
                body.getType(),
                body.isEnabled());
        return Response.ok(ScheduledJobView.of(created)).build();
    }

    /** Enable/disable a job. Body: {@code {"enabled": true|false}}. */
    @POST
    @Path("/{id}/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setEnabled(@PathParam("id") String id, Map<String, Object> body) {
        if (!isAdmin()) {
            return forbidden();
        }
        if (body == null || !(body.get("enabled") instanceof Boolean)) {
            return badRequest("body must be {\"enabled\": true|false}");
        }
        boolean enabled = (Boolean) body.get("enabled");
        ScheduledJobFile updated = jobStore.setEnabled(id, enabled);
        if (updated == null) {
            return notFound();
        }
        log.info("Admin {} set job {} enabled={}", currentUser(), id, enabled);
        return Response.ok(ScheduledJobView.of(updated)).build();
    }

    /** Delete a job. Idempotent — a missing job is a 404. */
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        if (!isAdmin()) {
            return forbidden();
        }
        boolean removed = jobStore.delete(id);
        if (!removed) {
            return notFound();
        }
        log.info("Admin {} deleted job {}", currentUser(), id);
        return Response.ok(Map.of("status", "deleted", "id", id)).build();
    }

    /**
     * Fire a job now (one synchronous run through the engine's owner-identity runner). Rate-limited per
     * admin. Returns the redacted post-run view. A job with no registered handler is recorded FAILED by
     * the engine (never throws), and — with NO real send handler wired in PR4 — a run sends nothing.
     */
    @POST
    @Path("/{id}/run")
    @Produces(MediaType.APPLICATION_JSON)
    public Response runNow(@PathParam("id") String id) {
        if (!isAdmin()) {
            return forbidden();
        }
        if (!runNowRateLimiter.tryAcquire(currentUser())) {
            return Response.status(429)
                    .entity(Map.of(
                            "error",
                            "Too many manual runs — limit is " + runNowRateLimiter.getMaxCalls() + " per "
                                    + (runNowRateLimiter.getWindowMs() / 1000) + "s. Please retry shortly."))
                    .build();
        }
        if (jobStore.load(id) == null) {
            return notFound();
        }
        ScheduledJobFile ran = jobScheduler.runNow(id);
        if (ran == null) {
            // Vanished between the load and the run, or a run was already in flight (overlap-skipped).
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "job could not be run (already running or removed)"))
                    .build();
        }
        log.info("Admin {} ran job {} now (status={})", currentUser(), id, ran.getLastStatus());
        return Response.ok(ScheduledJobView.of(ran)).build();
    }

    // ---- helpers ----

    private boolean isAdmin() {
        return userService != null && userService.isAdmin();
    }

    private List<String> currentRoles() {
        String[] roles = userService.getCurrentUserRoles();
        return roles == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(roles));
    }

    private String currentUser() {
        try {
            return userService == null ? "?" : userService.getActiveUsername();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "admin only"))
                .build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg))
                .build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "job not found"))
                .build();
    }
}
