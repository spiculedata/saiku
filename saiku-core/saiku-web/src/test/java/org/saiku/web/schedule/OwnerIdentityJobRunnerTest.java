/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.JobSchedule;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.OwnerUnavailableException;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.web.service.SessionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Owner-identity execution coverage for {@link OwnerIdentityJobRunner} (saiku#1809, PR3 — the
 * SEC-critical slice). Uses the REAL {@link SessionService#runAs} so a handler genuinely observes the
 * owner's roles in the {@link SecurityContextHolder}, and asserts the fail-closed / no-escalation /
 * live-role-re-resolution guarantees. Deterministic — no wall-clock timing.
 */
public class OwnerIdentityJobRunnerTest {

    private SessionService sessionService;

    @Before
    public void setUp() {
        sessionService = new SessionService();
        SecurityContextHolder.clearContext();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------- helpers ---------------- */

    /** A job owned by {@code owner} with the given persisted role snapshot. */
    private static ScheduledJobFile job(String owner, List<String> snapshot) {
        JobStore store = new JobStore((String) null);
        return store.create(
                owner,
                snapshot,
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "TEST",
                Map.of(),
                true);
    }

    /** Captures the authorities visible inside the handler (i.e. inside the runAs window). */
    private static final class CapturingHandler implements JobHandler {
        volatile List<String> rolesSeen;
        volatile Object principalSeen;
        volatile int calls;
        volatile Exception toThrow;

        @Override
        public void handle(ScheduledJobFile j) throws Exception {
            calls++;
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            principalSeen = a == null ? null : a.getPrincipal();
            List<String> roles = new ArrayList<>();
            if (a != null) {
                for (GrantedAuthority ga : a.getAuthorities()) {
                    roles.add(ga.getAuthority());
                }
            }
            rolesSeen = roles;
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    /* ---------------- tests ---------------- */

    @Test
    public void handlerSeesOwnerRoles_andPriorContextRestoredAfter() throws Exception {
        // Prior context: some unrelated admin sits in the holder before the run.
        Authentication prior = new PreAuthenticatedAuthenticationToken(
                "someAdmin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(prior);

        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of("ROLE_SALES", "ROLE_USER"));
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();

        runner.run(job("alice", List.of("ROLE_STALE")), h);

        assertEquals(1, h.calls);
        // Inside the handler the context was the OWNER's current roles.
        assertEquals(List.of("ROLE_SALES", "ROLE_USER"), h.rolesSeen);
        assertEquals("alice", h.principalSeen);
        // After the run the prior admin context is restored — no impersonation leak.
        assertSame(prior, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void reResolvesCurrentRoles_notStaleSnapshot() throws Exception {
        // The persisted snapshot still grants ROLE_ADMIN, but the live resolver says it was revoked.
        List<String> staleSnapshot = List.of("ROLE_ADMIN", "ROLE_USER");
        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of("ROLE_USER")); // revoked ADMIN
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();

        runner.run(job("alice", staleSnapshot), h);

        // The run used CURRENT roles — the revoked ROLE_ADMIN is absent.
        assertEquals(List.of("ROLE_USER"), h.rolesSeen);
        assertFalse("a revoked role must never be present in the run context", h.rolesSeen.contains("ROLE_ADMIN"));
    }

    @Test
    public void noEscalation_runContextIsExactlyOwnerRoles() throws Exception {
        // Even with an admin in the ambient context, the run gets EXACTLY the owner's roles — never
        // the admin's, never an ambient superuser token.
        SecurityContextHolder.getContext()
                .setAuthentication(new PreAuthenticatedAuthenticationToken(
                        "root", null, List.of(new SimpleGrantedAuthority("ROLE_SUPERUSER"))));

        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of("ROLE_USER"));
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();

        runner.run(job("bob", List.of("ROLE_USER")), h);

        assertEquals("run context must be exactly the owner's roles", List.of("ROLE_USER"), h.rolesSeen);
        assertFalse(h.rolesSeen.contains("ROLE_SUPERUSER"));
        assertEquals("bob", h.principalSeen);
    }

    @Test
    public void ownerDisabledOrMissing_throwsOwnerUnavailable_handlerNeverRuns() {
        OwnerIdentityResolver resolver = u -> OwnerIdentity.absent(); // gone / disabled
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();

        assertThrows(OwnerUnavailableException.class, () -> runner.run(job("ghost", List.of("ROLE_USER")), h));
        assertEquals("the handler must NEVER run for a missing/disabled owner", 0, h.calls);
    }

    @Test
    public void blankOwner_throwsOwnerUnavailable_neverImpersonates() {
        OwnerIdentityResolver resolver = u -> {
            throw new AssertionError("resolver must not be consulted for a blank owner");
        };
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();

        assertThrows(OwnerUnavailableException.class, () -> runner.run(job("", List.of()), h));
        assertEquals(0, h.calls);
    }

    @Test
    public void handlerExceptionPropagates_afterContextRestored() {
        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of("ROLE_USER"));
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);
        CapturingHandler h = new CapturingHandler();
        h.toThrow = new IllegalStateException("smtp down");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> runner.run(job("alice", List.of("ROLE_USER")), h));
        assertEquals("smtp down", thrown.getMessage());
        // Context restored even though the handler threw (no impersonation leak on the failure path).
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void resolverConsultedWithOwnerUsername() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        OwnerIdentityResolver resolver = u -> {
            asked.set(u);
            return OwnerIdentity.present(List.of("ROLE_USER"));
        };
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(sessionService, resolver);

        runner.run(job("carol", List.of("ROLE_STALE")), new CapturingHandler());
        assertEquals("carol", asked.get());
    }

    @Test
    public void nullDependencies_rejected() {
        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of());
        assertThrows(IllegalArgumentException.class, () -> new OwnerIdentityJobRunner(null, resolver));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OwnerIdentityJobRunner(sessionService, (OwnerIdentityResolver) null));
    }

    /** A session service that records the runAs args while still doing the real impersonation. */
    private static final class RecordingSessionService extends SessionService {
        volatile String runAsUser;
        volatile List<String> runAsRoles;

        @Override
        public <T> T runAs(String username, List<String> roles, Supplier<T> action) {
            runAsUser = username;
            runAsRoles = roles;
            return super.runAs(username, roles, action);
        }
    }

    @Test
    public void runAsInvokedWithCurrentRoles_notSnapshot() throws Exception {
        RecordingSessionService rec = new RecordingSessionService();
        OwnerIdentityResolver resolver = u -> OwnerIdentity.present(List.of("ROLE_CURRENT"));
        OwnerIdentityJobRunner runner = new OwnerIdentityJobRunner(rec, resolver);

        runner.run(job("dan", List.of("ROLE_SNAPSHOT")), new CapturingHandler());

        assertEquals("dan", rec.runAsUser);
        assertEquals(
                "runAs must receive the CURRENT roles, not the persisted snapshot",
                List.of("ROLE_CURRENT"),
                rec.runAsRoles);
        assertTrue(rec.runAsRoles != null && !rec.runAsRoles.contains("ROLE_SNAPSHOT"));
    }
}
