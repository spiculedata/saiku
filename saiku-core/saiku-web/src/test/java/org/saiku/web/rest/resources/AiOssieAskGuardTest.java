/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.ai.OssieAiAskService;
import org.saiku.web.security.ratelimit.AiRateLimiter;

/**
 * saiku#1459: the Ossie NL ask endpoint must carry the same cost-DoS guards as {@code /ai/ask}
 * (size cap + rate limit), so a client blocked on the MDX endpoint can't switch to
 * {@code /ai/ossie/ask} and reach the paid LLM provider unbounded. These guards fire before any
 * model call, so a configured-but-unwired askService is enough to exercise them.
 */
public class AiOssieAskGuardTest {

    private AiOssieResource resource;

    @Before
    public void setUp() {
        resource = new AiOssieResource();
        // saiku#2322: the pre-wiring guard default is now the fail-closed schema-only; these
        // tests exercise the size/rate guards, so hand the resource an explicit permissive guard.
        resource.setAiPolicyGuard(new org.saiku.service.olap.ai.AiPolicyGuard(org.saiku.service.olap.ai.AiPolicy.FULL));
        // A "configured" ask service so the endpoint gets past the 503 gate and reaches the guards.
        resource.setAskService(new OssieAiAskService() {
            @Override
            public boolean isConfigured() {
                return true;
            }
        });
    }

    private static Map<String, Object> body(String question) {
        return Map.of("connection", "sales", "model", "sales", "question", question);
    }

    @Test
    public void oversizeQuestionIsRejectedWith413BeforeTheLlmCall() {
        String huge = "x".repeat(9_000); // > MAX_QUESTION_CHARS (8_000)
        Response resp = resource.ask(body(huge));
        assertEquals(413, resp.getStatus());
    }

    @Test
    public void exhaustedRateLimitIsRejectedWith429() {
        // A limiter that always denies — the very first ask is rate-limited.
        resource.setAskRateLimiter(new AiRateLimiter() {
            @Override
            public boolean tryAcquire(String key) {
                return false;
            }
        });
        Response resp = resource.ask(body("show sales by region"));
        assertEquals(429, resp.getStatus());
    }

    @After
    public void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /**
     * saiku#2529: the daily cost budget gates the Ossie ask too — it was limiter-only, so the
     * budget's per-user and instance ceilings never applied on this surface. The first ask passes
     * the budget gate (it fails later, on the unwired discover service); the second is refused by
     * the budget before anything else runs.
     */
    @Test
    public void exhaustedBudgetIsRejectedWith429() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "ossie-budget-user", "pw"));
        resource.setAiCostBudget(new org.saiku.web.security.ratelimit.AiCostBudget(1, 0, 0, 0));
        resource.ask(body("show sales by region"));
        Response second = resource.ask(body("show sales by region"));
        assertEquals(429, second.getStatus());
    }

    @Test
    public void oversizeHistoryMessageIsRejected() {
        String hugeContent = "y".repeat(9_000); // > MAX_MESSAGE_CHARS (8_000)
        Map<String, Object> b = Map.of(
                "connection",
                "sales",
                "question",
                "and by channel?",
                "history",
                List.of(Map.of("role", "user", "content", hugeContent)));
        Response resp = resource.ask(b);
        assertEquals(413, resp.getStatus());
    }
}
