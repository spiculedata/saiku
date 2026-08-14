/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.JobSchedule;
import org.saiku.service.schedule.JobScheduler;
import org.saiku.service.schedule.JobStatus;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.NoOpJobHandler;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.user.UserService;
import org.saiku.web.service.SessionService;

/**
 * saiku#1809 PR4 — wiring smoke test for the LIVE scheduler. Constructs the {@link JobScheduler} the
 * SAME way {@code saiku-beans.xml} does — file/in-memory {@link JobStore}, a system {@link Clock}, the
 * owner-identity {@link OwnerIdentityJobRunner} (real {@link SessionService} + {@link
 * UserServiceOwnerIdentityResolver}), and a handler registry containing ONLY {@link NoOpJobHandler}
 * (keyed {@code "NOOP"}) — then verifies:
 *
 * <ul>
 *   <li>the ticker starts ({@link JobScheduler#start()}) and {@link JobScheduler#shutdown()} stops it
 *       cleanly;
 *   <li>a fire-now over an EMPTY store is a safe no-op (returns null, never throws);
 *   <li>a job whose {@code type} has NO registered handler is recorded FAILED — never a thrown
 *       exception that would kill the worker/ticker thread. This exercises the SAME execute path the
 *       ticker's worker uses.
 * </ul>
 *
 * <p><b>No mail is sent.</b> Only the no-op handler is wired — there is no send capability anywhere.
 */
public class SchedulerWiringTest {

    private JobScheduler scheduler;

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** Build the engine exactly as the Spring bean does. */
    private JobScheduler build(JobStore store) {
        UserService users = new StubUserService("alice", true, new String[] {"ROLE_USER"});
        OwnerIdentityResolver resolver = new UserServiceOwnerIdentityResolver(users);
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(new SessionService(), resolver);
        JobScheduler s = new JobScheduler(store, Clock.systemUTC(), runner);
        s.setHandlers(Map.<String, JobHandler>of("NOOP", new NoOpJobHandler()));
        return s;
    }

    @Test
    public void startsAndShutsDownCleanly() {
        scheduler = build(new JobStore((String) null));
        // start() begins the ticker; shutdown() stops it. Neither may throw, and shutdown is safe to
        // call again (idempotent). Reaching the end without an exception is the assertion.
        scheduler.start();
        scheduler.shutdown();
        scheduler.shutdown();
    }

    @Test
    public void runNowOverEmptyStore_isSafeNoOp() {
        scheduler = build(new JobStore((String) null));
        scheduler.start();
        // No such job — the scan/execute path must be a safe no-op (null, never a throw).
        assertNull(scheduler.runNow("AAAAAAAAAAAAAAAA"));
    }

    @Test
    public void unknownHandlerType_recordedFailed_neverThrows() {
        JobStore store = new JobStore((String) null);
        scheduler = build(store);
        scheduler.start();
        // A job whose type has no handler (only "NOOP" is registered).
        ScheduledJobFile job = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "UNREGISTERED_TYPE",
                Map.of(),
                true);
        // runNow goes through the SAME execute path the ticker's worker uses — it must record FAILED,
        // not throw, so the ticker/worker thread survives an unknown-type job.
        ScheduledJobFile ran = scheduler.runNow(job.getId());
        assertNotNull(ran);
        assertEquals(JobStatus.FAILED, ran.getLastStatus());
        assertTrue(ran.getLastError().contains("No handler"));
        // Still alive — a second run of a benign no-op type still works.
        ScheduledJobFile noop = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "NOOP",
                Map.of(),
                true);
        ScheduledJobFile ok = scheduler.runNow(noop.getId());
        assertEquals(JobStatus.OK, ok.getLastStatus());
    }

    /* ------------------------------ stub ------------------------------ */

    private static final class StubUserService extends UserService {
        private final SaikuUser user;
        private final String[] roles;

        StubUserService(String username, boolean enabled, String[] roles) {
            this.user = new SaikuUser();
            this.user.setUsername(username);
            this.user.setEnabled(enabled);
            this.roles = roles;
        }

        @Override
        public List<SaikuUser> getUsers() {
            List<SaikuUser> l = new ArrayList<>();
            l.add(user);
            return l;
        }

        @Override
        public String[] getRoles(SaikuUser u) {
            return roles;
        }
    }
}
