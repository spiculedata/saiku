/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.ossie.OssieDiscoverService;
import org.saiku.service.ossie.OssieModelDto;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.service.ossie.ai.OssieAsyncQueryService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2323 (CWE-863) — the async Ossie submit path must enforce {@code saiku.roles} at submit
 * time, against the role-filtered model, the same way the AI schema endpoint does. Before the fix
 * the resource validated against the unfiltered semantic, so a denied field was accepted with a
 * 202 and only (best-case) failed later on the worker — and before the worker's SecurityContext
 * propagation it did not even fail there.
 *
 * <p>The worker-side propagation itself is covered by {@code OssieAsyncQueryServiceTest} and
 * {@code OssieAsyncRlsIT}; this class covers the resource-layer edge: what the caller gets back
 * from {@code POST /query/execute-async}.
 */
public class AiOssieResourceAsyncRoleTest {

    private AiOssieResource resource;
    private OssieAsyncQueryService async;

    @Before
    public void setUp() {
        resource = new AiOssieResource();
        resource.setOssieDiscoverService(new StubDiscover());
        // The worker never runs in the deny test (validation fails first); the stub query service
        // returning null is fine for the accept case, which only asserts the 202 envelope.
        async = new OssieAsyncQueryService(new OssieQueryService());
        resource.setAsyncQueryService(async);
    }

    @After
    public void tearDown() {
        if (async != null) async.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    public void executeAsyncRejectsDeniedFieldAtSubmit() {
        authenticateAs("apac", "ROLE_APAC_ANALYST");
        OssieAiQueryRequest req = request("geography", "account_manager", "row_count");
        Response resp = resource.executeAsync(req);
        assertEquals("denied field must be refused at submit, got " + resp.getStatus(), 400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertEquals("VALIDATION_ERROR", entity.get("error"));
        assertTrue(
                "message must name the denied field, got: " + entity.get("message"),
                String.valueOf(entity.get("message")).contains("account_manager"));
    }

    @Test
    public void executeAsyncRejectsDeniedMetricAtSubmit() {
        authenticateAs("apac", "ROLE_APAC_ANALYST");
        OssieAiQueryRequest req = request("geography", "region", "exec_only");
        Response resp = resource.executeAsync(req);
        assertEquals("denied metric must be refused at submit, got " + resp.getStatus(), 400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertTrue(
                "message must name the denied metric, got: " + entity.get("message"),
                String.valueOf(entity.get("message")).contains("exec_only"));
    }

    @Test
    public void executeAsyncStillAcceptsVisibleField() {
        // Over-filtering guard: the role-filtered validation must not reject a field the
        // caller's roles permit.
        authenticateAs("apac", "ROLE_APAC_ANALYST");
        OssieAiQueryRequest req = request("geography", "region", "row_count");
        Response resp = resource.executeAsync(req);
        assertEquals("visible field must still submit, got " + resp.getStatus(), 202, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
        assertNotNull(entity.get("queryId"));
    }

    @Test
    public void executeAsyncAllowListedMetricStillRefusedWithoutRoles() {
        // An anonymous/role-less submitter is unrestricted by row predicates and unaffected by a
        // deny list naming a role it doesn't hold — but a non-empty allow list still blocks, and
        // the submit-time role-filtered validation must enforce that too.
        SecurityContextHolder.clearContext();
        OssieAiQueryRequest req = request("geography", "region", "exec_only");
        Response resp = resource.executeAsync(req);
        assertEquals(400, resp.getStatus());
    }

    private static OssieAiQueryRequest request(String dataset, String field, String metric) {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection("Geo");
        OssieAiQueryRequest.FieldRef f = new OssieAiQueryRequest.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        req.getRows().add(f);
        OssieAiQueryRequest.MetricRef v = new OssieAiQueryRequest.MetricRef();
        v.setMetric(metric);
        req.getValues().add(v);
        return req;
    }

    private static void authenticateAs(String user, String... authorities) {
        List<SimpleGrantedAuthority> auths =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "n/a", auths));
    }

    /** The issue's minimal GEO fixture: denied field, denied metric, APAC row predicate. */
    static final class StubDiscover extends OssieDiscoverService {
        @Override
        public OssieModelDto getModel(String connectionName) {
            OssieModelDto semantic = new OssieModelDto();
            semantic.setConnection("Geo");
            semantic.setName("GEO");
            OssieModelDto.Dataset geography = new OssieModelDto.Dataset();
            geography.setName("geography");
            OssieModelDto.Field region = new OssieModelDto.Field();
            region.setName("region");
            region.setExpression("REGION");
            geography.getFields().add(region);
            OssieModelDto.Field accountManager = new OssieModelDto.Field();
            accountManager.setName("account_manager");
            accountManager.setExpression("ACCOUNT_MANAGER");
            accountManager.setDenyRoles(List.of("ROLE_APAC_ANALYST"));
            geography.getFields().add(accountManager);
            geography
                    .getRowPredicates()
                    .add(new OssieModelDto.RowPredicate("ROLE_APAC_ANALYST", "REGION IN ('APAC', 'Japan')"));
            semantic.getDatasets().add(geography);
            OssieModelDto.Metric rowCount = new OssieModelDto.Metric();
            rowCount.setName("row_count");
            rowCount.setExpression("COUNT(*)");
            semantic.getMetrics().add(rowCount);
            OssieModelDto.Metric execOnly = new OssieModelDto.Metric();
            execOnly.setName("exec_only");
            execOnly.setExpression("SUM(\"geography\".\"X\")");
            execOnly.setAllowRoles(List.of("ROLE_EXEC"));
            semantic.getMetrics().add(execOnly);
            return semantic;
        }
    }
}
