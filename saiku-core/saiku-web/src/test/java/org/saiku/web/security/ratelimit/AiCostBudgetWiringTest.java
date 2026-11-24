/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.ask.AiAskApi;
import org.saiku.web.rest.resources.AiExplainResource;
import org.saiku.web.rest.resources.AiOssieResource;
import org.saiku.web.rest.resources.AiQueryResource;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2529 wiring lock. The daily AI cost budget (#1918) was a {@code new AiCostBudget()} field
 * of the {@code scope="request"} {@code aiQueryResource} bean: every HTTP request built a fresh
 * budget with empty counters, so no ceiling — calls per user, tokens per user, tokens per
 * instance, chain concurrency — could ever trip. The fix is the same one #1913 applied to the
 * per-minute limiters: one singleton bean injected into every resource that reaches the provider.
 *
 * <p>This test loads a mirror of the {@code saiku-beans.xml} wiring (same pattern — and same drift
 * caveat — as {@code org.saiku.web.email.AiRateLimiterWiringTest}) and asserts that:
 *
 * <ol>
 *   <li>{@code aiQueryResource}, {@code aiOssieResource} and {@code aiExplainResource} all receive
 *       the SAME singleton budget (drop one {@code <property>} and this fails),</li>
 *   <li>the budget trips across request boundaries — spent on one request-scoped instance, still
 *       enforced on the next,</li>
 *   <li>the budget keys on the AUTHENTICATED PRINCIPAL, taken from the Spring Security context —
 *       two users behind one address get separate buckets (the old request-derived
 *       {@code getRemoteUser()} was null on the unwrapped container request, which made the
 *       budget fail open), and</li>
 *   <li>the token and chain-concurrency ceilings are enforced against the shared singleton.</li>
 * </ol>
 */
public class AiCostBudgetWiringTest {

    private static long unique = 0;

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /** bean id → owning resource class → budget field, all must hold the SAME singleton. */
    private static final Object[][] CONSUMERS = {
        {"aiQueryResource", AiQueryResource.class, "aiCostBudget"},
        {"aiQueryResource2", AiQueryResource.class, "aiCostBudget"},
        {"aiOssieResource", AiOssieResource.class, "aiCostBudget"},
        {"aiExplainResource", AiExplainResource.class, "aiCostBudget"},
    };

    @Test
    public void everyProviderReachingResourceIsWiredToTheSharedBudget() throws Exception {
        try (ClassPathXmlApplicationContext ctx = context()) {
            Object singleton = ctx.getBean("aiCostBudget");
            for (Object[] row : CONSUMERS) {
                Object resource = ctx.getBean((String) row[0]);
                Object budget = field((Class<?>) row[1], resource, (String) row[2]);
                assertNotNull(row[0] + "." + row[2] + " must be injected", budget);
                assertSame(row[0] + "." + row[2] + " must be the SHARED singleton", singleton, budget);
            }
        }
    }

    /**
     * The bug itself, end to end through the wired context: with {@code maxCallsPerUserPerDay=2},
     * the third {@code POST /ai/ask} from one user answers 429 with the budget reason — and a
     * second user is unaffected. Before the fix, the per-request budget never tripped and every
     * call reached the next step.
     */
    @Test
    public void dailyCallCeilingTripsAcrossRequestBoundaries() {
        try (ClassPathXmlApplicationContext ctx = context()) {
            String alice = "budget-alice-" + ++unique;
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(alice, "pw"));

            AiQueryResource first = ctx.getBean("aiQueryResource", AiQueryResource.class);
            AiQueryResource second = ctx.getBean("aiQueryResource2", AiQueryResource.class);
            // Deliberately alternate instances: the ceiling must live in the SHARED budget, not in
            // either resource. askService is unwired, so a call that PASSES the budget gate falls
            // through to the 503 "not configured" answer — that 503 is the pass mark.
            assertEquals(503, first.ask(request(alice)).getStatus());
            assertEquals(503, second.ask(request(alice)).getStatus());

            Response third = first.ask(request(alice));
            assertEquals("third ask from the same user must hit the shared budget", 429, third.getStatus());
            AiAskApi.AskResponse out = (AiAskApi.AskResponse) third.getEntity();
            assertTrue(out.isDegraded());
            assertTrue(
                    "the refusal must carry the budget reason, got: " + out.getReason(),
                    out.getReason().contains("Daily AI ask request budget exhausted"));
            assertTrue(out.getReason().contains("limit 2"));
        }
    }

    /** Two users behind ONE address: the budget principal is the user name, so buckets are separate. */
    @Test
    public void twoUsersBehindOneAddressGetSeparateBuckets() {
        try (ClassPathXmlApplicationContext ctx = context()) {
            String alice = "budget-nat-alice-" + ++unique;
            String bob = "budget-nat-bob-" + ++unique;
            bindAddress("10.0.7.2529"); // same NAT egress for both
            AiQueryResource resource = ctx.getBean("aiQueryResource", AiQueryResource.class);

            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(alice, "pw"));
            assertEquals(503, resource.ask(request(alice)).getStatus());
            assertEquals(503, resource.ask(request(alice)).getStatus());
            assertEquals(
                    "alice's third ask is over HER budget",
                    429,
                    resource.ask(request(alice)).getStatus());

            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(bob, "pw"));
            assertEquals(
                    "bob shares alice's address but not her budget — must NOT be refused for it",
                    503,
                    resource.ask(request(bob)).getStatus());
        }
    }

    /** The token ceiling is charged on the shared singleton and enforced from any request instance. */
    @Test
    public void tokenCeilingIsSharedAcrossRequestBoundaries() {
        try (ClassPathXmlApplicationContext ctx = context()) {
            String carol = "budget-carol-" + ++unique;
            AiCostBudget budget = ctx.getBean("aiCostBudget", AiCostBudget.class);
            // Charged on the singleton — where a completed ask's recordUsage lands.
            budget.recordUsage(carol, 10_000L, 0L); // test ceiling: 1,000 tokens per user per day

            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(carol, "pw"));
            AiQueryResource nextRequest = ctx.getBean("aiQueryResource2", AiQueryResource.class);
            Response resp = nextRequest.ask(request(carol));
            assertEquals("token spend recorded on one request must bind the next", 429, resp.getStatus());
            AiAskApi.AskResponse out = (AiAskApi.AskResponse) resp.getEntity();
            assertTrue(out.getReason().contains("Daily AI token budget exhausted"));
        }
    }

    /** The chain-concurrency cap lives on the shared singleton, so it bounds the whole instance. */
    @Test
    public void chainConcurrencyCapIsSharedAcrossRequestBoundaries() {
        try (ClassPathXmlApplicationContext ctx = context()) {
            AiCostBudget budget = ctx.getBean("aiCostBudget", AiCostBudget.class);
            assertEquals(1, budget.getMaxConcurrentChains());
            AiCostBudget.ChainSlot first = budget.tryAcquireChainSlot();
            assertNotNull(first);
            AiCostBudget.ChainSlot second = budget.tryAcquireChainSlot();
            assertNotSame(first, second);
            assertEquals("the second chain must be refused while the first holds the only slot", null, second);
            first.close();
            assertNotNull("the slot is released on close", budget.tryAcquireChainSlot());
        }
    }

    // ------------------------------------------------------------------

    private static ClassPathXmlApplicationContext context() {
        return new ClassPathXmlApplicationContext("ratelimit-wiring-test.xml");
    }

    private static AiAskApi.AskRequest request(String question) {
        AiAskApi.AskRequest body = new AiAskApi.AskRequest();
        body.setQuestion(question);
        body.setCube(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        return body;
    }

    /** Bind a request attribute with only {@code getRemoteAddr} populated — no authenticated user. */
    private static void bindAddress(String ip) {
        jakarta.servlet.http.HttpServletRequest req =
                (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                        AiCostBudgetWiringTest.class.getClassLoader(),
                        new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
                        (proxy, method, args) -> "getRemoteAddr".equals(method.getName()) ? ip : null);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
