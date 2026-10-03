/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import org.junit.Test;
import org.saiku.web.rest.resources.AiOssieResource;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.rest.resources.SchedulerResource;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * saiku#1913 wiring lock. Every per-endpoint limiter used to be a {@code new AiRateLimiter(...)}
 * field of a {@code scope="request"} resource, so a fresh, EMPTY bucket map was built per HTTP
 * request: {@code tryAcquire} always saw count = 1 and the cap never tripped. The budget only
 * becomes real once each resource is handed the shared singleton bean.
 *
 * <p>This test loads a mirror of the {@code saiku-beans.xml} limiter wiring (same pattern — and
 * same drift caveat — as {@code SchemaGenWiringSmokeTest}) and asserts that:
 *
 * <ol>
 *   <li>every rate-limited resource receives a SHARED-store limiter with the expected store name
 *       (drop one {@code <property>} and this fails, which is the whole point), and
 *   <li>two instances of the same resource — i.e. two HTTP requests — share one budget.
 * </ol>
 */
public class AiRateLimiterWiringTest {

    /** bean id, owning resource class, limiter field, expected shared store name. */
    private static final Object[][] EXPECTED = {
        {"unsubscribeResource", UnsubscribeResource.class, "rateLimiter", "mail.unsubscribe"},
        {"consentConfirmResource", ConsentConfirmResource.class, "rateLimiter", "mail.consent"},
        {
            "consentConfirmResource", ConsentConfirmResource.class, "addressRateLimiter", "mail.consent.address"
        },
        {"mailConfigResource", MailConfigResource.class, "testSendRateLimiter", "mail.test"},
        {"mailSendResource", MailSendResource.class, "inviteRateLimiter", "mail.invite"},
        {"mailSendResource", MailSendResource.class, "sendRateLimiter", "mail.send"},
        {"emailResource", EmailResource.class, "emailRateLimiter", "mail.email"},
        {"schedulerResource", SchedulerResource.class, "runNowRateLimiter", "jobs.runNow"},
        {"aiQueryResource", AiQueryResource.class, "askRateLimiter", "ai.query.ask"},
        {"aiOssieResource", AiOssieResource.class, "askRateLimiter", "ai.ossie.ask"},
    };

    @Test
    public void everyRateLimitedResourceIsWiredToASharedLimiter() throws Exception {
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ratelimit-wiring-test.xml")) {
            for (Object[] row : EXPECTED) {
                String beanId = (String) row[0];
                Class<?> resourceClass = (Class<?>) row[1];
                String field = (String) row[2];
                String storeName = (String) row[3];

                Object resource = ctx.getBean(beanId);
                AiRateLimiter limiter = (AiRateLimiter) field(resourceClass, resource, field);
                assertNotNull(beanId + "." + field + " must be injected", limiter);
                assertEquals(
                        beanId + "." + field + " must count in the shared '" + storeName + "' store",
                        storeName,
                        limiter.getStoreName());
            }
        }
    }

    /**
     * The bug itself, end to end through the resource: the budget is spent on one instance and
     * still applies to the NEXT one. A per-instance store would let every call through.
     */
    @Test
    public void twoResourceInstancesShareOneBudget() throws Exception {
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ratelimit-wiring-test.xml")) {
            UnsubscribeResource first = ctx.getBean("unsubscribeResource", UnsubscribeResource.class);
            UnsubscribeResource second = ctx.getBean("unsubscribeResource2", UnsubscribeResource.class);
            assertSame("both instances must be wired to the SAME singleton", limiterOf(first), limiterOf(second));

            int budget = limiterOf(first).getMaxCalls();
            // A unique client IP keeps this test off the other keys in the process-wide store.
            first.setRequest(requestFromIp("10.0.0.1913"));
            second.setRequest(requestFromIp("10.0.0.1913"));
            for (int i = 0; i < budget; i++) {
                Response resp = (i % 2 == 0 ? first : second).unsubscribePost("alice@example.com", "token");
                assertEquals("call " + (i + 1) + " is within the shared budget", 200, resp.getStatus());
            }
            Response overBudget = second.unsubscribePost("alice@example.com", "token");
            assertEquals("the cap must survive the request boundary (saiku#1913)", 429, overBudget.getStatus());
        }
    }

    @Test
    public void limiterBeanCarriesTheEndpointBudget() {
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ratelimit-wiring-test.xml")) {
            AiRateLimiter limiter = ctx.getBean("mailSendRateLimiter", AiRateLimiter.class);
            assertEquals("mail.send default is 3/min", 3, limiter.getMaxCalls());
            assertEquals(60_000L, limiter.getWindowMs());
        }
    }

    private static AiRateLimiter limiterOf(UnsubscribeResource resource) throws Exception {
        return (AiRateLimiter) field(UnsubscribeResource.class, resource, "rateLimiter");
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Minimal {@link jakarta.servlet.http.HttpServletRequest} stub exposing just {@code getRemoteAddr}. */
    private static jakarta.servlet.http.HttpServletRequest requestFromIp(String ip) {
        return (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                AiRateLimiterWiringTest.class.getClassLoader(),
                new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> "getRemoteAddr".equals(method.getName()) ? ip : null);
    }
}
