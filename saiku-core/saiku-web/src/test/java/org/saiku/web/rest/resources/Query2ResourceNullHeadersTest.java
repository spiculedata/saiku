/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;

/**
 * Regression coverage for the code path used by {@link ExporterResource}: it
 * invokes {@link Query2Resource#execute(ThinQuery, jakarta.ws.rs.core.HttpHeaders)}
 * with a literal {@code null} HttpHeaders argument (the exporter has no request
 * headers to forward). The null must not trip an NPE in the Arrow content
 * negotiation branch, and the method must fall through to the plain JSON
 * response just like it did before the Arrow work landed.
 *
 * <p>We reuse the {@code StubThinQueryService} pattern from
 * {@link Query2ResourceArrowTest} so we don't need a real OLAP connection.
 */
public class Query2ResourceNullHeadersTest {

    @Test
    public void executeWithNullHeadersReturnsJsonResponse() throws Exception {
        Query2Resource resource = new Query2Resource();
        ThinQuery tq = new ThinQuery();
        tq.setName("null-headers-test");
        tq.setMdx("SELECT FROM [stub]");
        resource.setThinQueryService(new Query2ResourceArrowTest.StubThinQueryService(tq, new StubCellSet().cellSet()));

        // This is the exact call shape used by ExporterResource#exportJson /
        // exportCsv / exportExcel after commit 23f1ebfe.
        Response resp = resource.execute(tq, null);

        assertEquals(200, resp.getStatus());
        assertNotNull("media type must be set", resp.getMediaType());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
        // Must NOT have taken the Arrow streaming branch — that would be a
        // regression in the null-safety check inside clientPrefersArrow().
        assertFalse("null headers must not route to arrow branch", resp.getEntity() instanceof StreamingOutput);
    }

    /** Minimal CellSet stub wrapper — the arrow test's builder is private, so
     * we just need any non-null CellSet for the stub service. The JSON branch
     * only consults {@code thinQueryService.execute(tq)} (which returns an
     * empty CellDataSet) plus {@code getContext(name).getOlapQuery()}, so the
     * CellSet itself is never read on this path. */
    private static class StubCellSet {
        org.olap4j.CellSet cellSet() {
            return (org.olap4j.CellSet) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {org.olap4j.CellSet.class}, (p, m, a) -> {
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    });
        }
    }
}
