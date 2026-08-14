/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schedule.JobRunner;
import org.saiku.service.schedule.JobSchedule;
import org.saiku.service.schedule.JobScheduler;
import org.saiku.service.schedule.JobStatus;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.ScheduledJobView;
import org.saiku.service.user.UserService;

/**
 * saiku#1809 PR4 — admin scheduler endpoint. Drives {@link SchedulerResource} against an in-memory
 * {@link JobStore} + a live {@link JobScheduler} (DIRECT runner, no send handler). Locks: admin-only
 * (non-admin -> 403), server-minted owner, the response is always a redacted {@link ScheduledJobView}
 * (never the on-disk file / payload values), and a fire-now of a job whose type has no handler is
 * recorded FAILED — never a thrown exception.
 *
 * <p>NO mail is sent by any path here — no send handler exists.
 */
public class SchedulerResourceTest {

    private JobStore store;
    private JobScheduler scheduler;
    private StubUserService users;

    @Before
    public void setUp() {
        // In-memory store (null home) + a live engine with NO handlers registered (DIRECT runner).
        store = new JobStore((String) null);
        scheduler = new JobScheduler(store, Clock.systemUTC(), JobRunner.DIRECT);
        users = new StubUserService();
    }

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private SchedulerResource resource() {
        SchedulerResource r = new SchedulerResource();
        r.setJobStore(store);
        r.setJobScheduler(scheduler);
        r.setUserService(users);
        return r;
    }

    private static ScheduledJobRequest req(String type, Map<String, Object> payload) {
        ScheduledJobRequest b = new ScheduledJobRequest();
        b.setType(type);
        b.setSchedule(new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, null));
        b.setPayload(payload);
        b.setEnabled(true);
        return b;
    }

    @Test
    public void create_asAdmin_persists_serverMintedOwner_redactedView() {
        Response resp = resource().create(req("EMAIL_SUBSCRIPTION", Map.of("dashboard", "sales", "to", "hint")));
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getEntity() instanceof ScheduledJobView);
        ScheduledJobView view = (ScheduledJobView) resp.getEntity();
        // Owner is the calling admin, never a client-supplied value.
        assertEquals("admin", view.ownerUsername());
        assertTrue(view.ownerRolesSnapshot().contains("ROLE_ADMIN"));
        // Redacted: only the payload KEY NAMES appear, never the values.
        assertTrue(view.payloadKeys().contains("dashboard"));
        assertTrue(view.payloadKeys().contains("to"));
        assertNotNull(view.id());
        // Persisted in the store.
        assertNotNull(store.load(view.id()));
    }

    @Test
    public void list_asAdmin_returnsRedactedViews() {
        resource().create(req("EMAIL_SUBSCRIPTION", Map.of("secret", "value")));
        Response resp = resource().list();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ScheduledJobView> views = (List<ScheduledJobView>) resp.getEntity();
        assertEquals(1, views.size());
        // The on-disk payload values are never exposed — keys only.
        assertEquals(List.of("secret"), views.get(0).payloadKeys());
    }

    @Test
    public void setEnabled_togglesJob() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        Response resp = resource().setEnabled(created.id(), Map.of("enabled", Boolean.FALSE));
        assertEquals(200, resp.getStatus());
        assertFalse(((ScheduledJobView) resp.getEntity()).enabled());
        assertFalse(store.load(created.id()).isEnabled());
    }

    @Test
    public void setEnabled_badBody_is400() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        assertEquals(
                400, resource().setEnabled(created.id(), Map.of("nope", "x")).getStatus());
    }

    @Test
    public void setEnabled_unknownId_is404() {
        assertEquals(
                404,
                resource()
                        .setEnabled("AAAAAAAAAAAAAAAA", Map.of("enabled", Boolean.TRUE))
                        .getStatus());
    }

    @Test
    public void delete_removesJob() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        assertEquals(200, resource().delete(created.id()).getStatus());
        assertEquals(404, resource().delete(created.id()).getStatus());
    }

    @Test
    public void runNow_unknownHandlerType_recordedFailed_neverThrows() {
        // No handler is registered for this type — the engine must record FAILED and NOT throw, so the
        // ticker/worker survives. The endpoint returns 200 with a FAILED view.
        ScheduledJobView created = (ScheduledJobView)
                resource().create(req("NO_SUCH_HANDLER_TYPE", Map.of())).getEntity();
        Response resp = resource().runNow(created.id());
        assertEquals(200, resp.getStatus());
        ScheduledJobView view = (ScheduledJobView) resp.getEntity();
        assertEquals(JobStatus.FAILED, view.lastStatus());
        assertNotNull(view.lastError());
        assertTrue(view.lastError().contains("No handler"));
    }

    @Test
    public void runNow_unknownId_is404() {
        assertEquals(404, resource().runNow("AAAAAAAAAAAAAAAA").getStatus());
    }

    /* ------------------------------ non-admin gate ------------------------------ */

    @Test
    public void nonAdmin_list_is403() {
        users.admin = false;
        assertEquals(403, resource().list().getStatus());
    }

    @Test
    public void nonAdmin_create_is403() {
        users.admin = false;
        Response resp = resource().create(req("X", Map.of()));
        assertEquals(403, resp.getStatus());
        assertTrue("nothing written", store.listAll().isEmpty());
    }

    @Test
    public void nonAdmin_setEnabled_is403() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        users.admin = false;
        assertEquals(
                403,
                resource()
                        .setEnabled(created.id(), Map.of("enabled", Boolean.FALSE))
                        .getStatus());
    }

    @Test
    public void nonAdmin_delete_is403() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        users.admin = false;
        assertEquals(403, resource().delete(created.id()).getStatus());
        assertNotNull("not deleted", store.load(created.id()));
    }

    @Test
    public void nonAdmin_runNow_is403() {
        ScheduledJobView created =
                (ScheduledJobView) resource().create(req("X", Map.of())).getEntity();
        users.admin = false;
        assertEquals(403, resource().runNow(created.id()).getStatus());
    }

    @Test
    public void create_nullBody_is400() {
        assertEquals(400, resource().create(null).getStatus());
    }

    @Test
    public void create_blankType_is400() {
        assertEquals(400, resource().create(req("  ", Map.of())).getStatus());
    }

    /* ------------------------------ stub ------------------------------ */

    private static final class StubUserService extends UserService {
        boolean admin = true;

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public String getActiveUsername() {
            return "admin";
        }

        @Override
        public String[] getCurrentUserRoles() {
            return new String[] {"ROLE_USER", "ROLE_ADMIN"};
        }
    }
}
