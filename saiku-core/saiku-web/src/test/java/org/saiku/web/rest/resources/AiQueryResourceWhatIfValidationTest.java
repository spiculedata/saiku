/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;

/**
 * saiku#2550j — the what-if endpoint concatenates client identifiers into MDX and
 * mutates per-connection scenario state, so every name is validated BEFORE it can
 * reach a query and the whole scenario sequence is serialised per connection. This
 * pins the validation gate: a name that doesn't parse as a full MDX unique name
 * is a 400 and NEVER touches the connection. Level membership is checked against
 * query rows after the connection has been reached.
 */
public class AiQueryResourceWhatIfValidationTest {

    private AiQueryResource resource;

    @Before
    public void setUp() {
        resource = new AiQueryResource();
        resource.setAiPolicyGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));
        // The connection must never be reached when validation rejects a name.
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public org.olap4j.OlapConnection getNativeConnection(String name) {
                throw new AssertionError("a rejected request must never touch the connection");
            }
        });
    }

    private static Map<String, Object> body(String measure, String level, String member) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("connection", "foodmart");
        b.put("cube", "Sales");
        b.put("measure", measure);
        b.put("level", level);
        b.put("member", member);
        b.put("value", 100);
        return b;
    }

    private static void assertBadRequestField(Response resp, String field) throws Exception {
        assertEquals(400, resp.getStatus());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp.getEntity());
        assertTrue("expected a " + field + " rejection, got " + json, json.contains("\"field\":\"" + field + "\""));
    }

    @Test
    public void validNames_passValidation_andOnlyThenReachTheConnection() {
        // Control: names in the right shape get PAST validation (and then fail
        // naturally when the (stub) connection lookup throws, which is the
        // assertion the other tests rely on never happening).
        try {
            resource.scenarioWhatIf(
                    body("Store Sales", "[Product].[Product].[Therapeutic Class]", "[Product].[Product].[Oncology]"));
        } catch (AssertionError reachedConnection) {
            // expected — validation passed and the (never-wired) connection was touched
            return;
        }
        throw new AssertionError("expected the stubbed connection to be reached");
    }

    @Test
    public void measureIsAPlainName_noBrackets() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("[Measures].[X]] --", "[Time].[Time]", "[Time].[Time].[1997]")),
                "measure");
    }

    @Test
    public void cubeIsAPlainName_noBrackets() throws Exception {
        Map<String, Object> b = body("Store Sales", "[Time].[Time]", "[Time].[Time].[1997]");
        b.put("cube", "Sales] ON ROWS FROM [Other");
        assertBadRequestField(resource.scenarioWhatIf(b), "cube");
    }

    @Test
    public void levelMustBeAUniqueName() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("Store Sales", "Time.Members]] --", "[Time].[Time].[1997]")), "level");
    }

    @Test
    public void memberMustBeAUniqueName() throws Exception {
        assertBadRequestField(
                resource.scenarioWhatIf(body("Store Sales", "[Time].[Time]", "[Time].[Time]] --")), "member");
    }

    @Test
    public void twoAliasesForOneNativeConnectionCannotOverlapScenarioState() throws Exception {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondLookup = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean();
        org.olap4j.OlapConnection nativeConnection = (org.olap4j.OlapConnection) Proxy.newProxyInstance(
                org.olap4j.OlapConnection.class.getClassLoader(),
                new Class<?>[] {org.olap4j.OlapConnection.class},
                (proxy, method, args) -> {
                    if ("getScenario".equals(method.getName())) {
                        if (active.incrementAndGet() > 1) overlap.set(true);
                        if (entries.getAndIncrement() == 0) {
                            firstInside.countDown();
                            releaseFirst.await(2, TimeUnit.SECONDS);
                        }
                        active.decrementAndGet();
                        return null;
                    }
                    if ("createScenario".equals(method.getName())) throw new SQLException("test stop");
                    return null;
                });
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public org.olap4j.OlapConnection getNativeConnection(String name) {
                if ("alias".equals(name)) secondLookup.countDown();
                return nativeConnection;
            }
        });
        Map<String, Object> first =
                body("Store Sales", "[Product].[Product].[Therapeutic Class]", "[Product].[Product].[Oncology]");
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("connection", "alias");
        Thread one = new Thread(() -> resource.scenarioWhatIf(first));
        Thread two = new Thread(() -> resource.scenarioWhatIf(second));
        one.start();
        assertTrue(firstInside.await(2, TimeUnit.SECONDS));
        two.start();
        try {
            assertTrue(secondLookup.await(2, TimeUnit.SECONDS));
            // With locks keyed by request spelling, the second caller enters while the
            // first is paused in getScenario. One native connection must serialize them.
            Thread.sleep(150);
        } finally {
            releaseFirst.countDown();
        }
        one.join(2000);
        two.join(2000);
        assertTrue(!one.isAlive() && !two.isAlive());
        assertEquals(2, entries.get());
        assertTrue("scenario access overlapped across aliases", !overlap.get());
    }
}
