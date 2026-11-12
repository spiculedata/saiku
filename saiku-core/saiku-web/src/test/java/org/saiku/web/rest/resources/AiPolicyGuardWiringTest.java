/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import org.junit.Test;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * saiku#2322 wiring lock. The {@code aiOssieResource} bean definition in {@code saiku-beans.xml}
 * never received the {@code aiPolicyGuard} singleton — the resource kept a hard-coded permissive
 * {@code FULL} default and the operator's {@code SAIKU_AI_POLICY} was silently ignored for the
 * whole Ossie REST/MCP surface (the MDX {@code aiQueryResource} was wired; Ossie was not).
 *
 * <p>This test loads a mirror of the production policy-guard wiring (same pattern — and same
 * drift caveat — as {@code AiRateLimiterWiringTest}) and asserts that:
 *
 * <ol>
 *   <li>both AI resources receive the SAME shared {@code aiPolicyGuard} singleton (drop the
 *       {@code <property>} from the XML and this fails, which is the whole point), and
 *   <li>an {@link AiOssieResource} wired WITHOUT the property fails closed: its pre-wiring
 *       default is {@link AiPolicy#DEFAULT} (schema-only), not the old permissive {@code FULL}.
 * </ol>
 */
public class AiPolicyGuardWiringTest {

    private static Object field(Class<?> clazz, Object target, String name) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    public void aiOssieResourceReceivesTheSharedPolicyGuard() throws Exception {
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ai-policy-wiring-test.xml")) {
            Object shared = ctx.getBean("aiPolicyGuard");
            assertNotNull(shared);
            Object query = ctx.getBean("aiQueryResource");
            Object ossie = ctx.getBean("aiOssieResource");
            assertSame(
                    "aiQueryResource must receive the aiPolicyGuard singleton",
                    shared,
                    field(AiQueryResource.class, query, "aiPolicyGuard"));
            assertSame(
                    "aiOssieResource must receive the aiPolicyGuard singleton (saiku#2322)",
                    shared,
                    field(AiOssieResource.class, ossie, "aiPolicyGuard"));
        }
    }

    @Test
    public void unwiredOssieResourceFailsClosedToSchemaOnly() throws Exception {
        // The negative case: a context whose aiOssieResource definition LACKS the property must
        // not yield the old permissive FULL default — the class default is AiPolicy.DEFAULT
        // (schema-only), so an unguarded instance refuses aggregated data out of the box.
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("ai-policy-wiring-test.xml")) {
            // sanity: the wired context really hands over the singleton
            AiPolicyGuard wired = (AiPolicyGuard) ctx.getBean("aiPolicyGuard");
            assertNotNull(wired);
        }
        AiOssieResource unwired = new AiOssieResource();
        AiPolicyGuard guard = (AiPolicyGuard) field(AiOssieResource.class, unwired, "aiPolicyGuard");
        assertEquals(
                "pre-wiring default must be the fail-closed AiPolicy.DEFAULT (schema-only)",
                AiPolicy.SCHEMA_ONLY,
                guard.current());
    }
}
