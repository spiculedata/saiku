/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.olap.query2.ThinNamedSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.service.olap.ThinQueryService;

/**
 * saiku#824 — {@code POST/GET/DELETE /saiku/api/query/{name}/sets}. Named sets so
 * the alternate UI can manage a query model's {@code WITH SET} entries without a
 * full {@code /enrich} round-trip. Uses a real {@link ThinQueryService} seeded via
 * {@code registerExternalContext} — no live OLAP connection is needed for these
 * endpoints since they only touch the in-memory {@link ThinQueryModel}.
 */
public class Query2ResourceNamedSetsTest {

    private static Query2Resource resourceWithQuery(String queryName) {
        Query2Resource resource = new Query2Resource();
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName(queryName);
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);
        resource.setThinQueryService(svc);
        return resource;
    }

    @Test
    public void postAddsANamedSetAndReturns201() {
        Query2Resource resource = resourceWithQuery("q1");

        Response resp = resource.addNamedSet("q1", new ThinNamedSet("Top5", "Head([Product].Members, 5)"));

        assertEquals(201, resp.getStatus());
        ThinNamedSet body = (ThinNamedSet) resp.getEntity();
        assertEquals("Top5", body.getName());
    }

    @Test
    public void getListsTheNamedSets() {
        Query2Resource resource = resourceWithQuery("q2");
        resource.addNamedSet("q2", new ThinNamedSet("A", "[Measures].Members"));
        resource.addNamedSet("q2", new ThinNamedSet("B", "[Measures].Members"));

        Response resp = resource.getNamedSets("q2");

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ThinNamedSet> body = (List<ThinNamedSet>) resp.getEntity();
        assertEquals(2, body.size());
    }

    @Test
    public void deleteRemovesTheNamedSetAndReturns410() {
        Query2Resource resource = resourceWithQuery("q3");
        resource.addNamedSet("q3", new ThinNamedSet("Top5", "[Measures].Members"));

        Response resp = resource.deleteNamedSet("q3", "Top5");

        assertEquals(410, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ThinNamedSet> body =
                (List<ThinNamedSet>) resource.getNamedSets("q3").getEntity();
        assertTrue(body.isEmpty());
    }

    @Test
    public void deleteUnknownNamedSetReturns404() {
        Query2Resource resource = resourceWithQuery("q4");

        Response resp = resource.deleteNamedSet("q4", "Nope");

        assertEquals(404, resp.getStatus());
    }

    @Test
    public void postWithBlankNameReturns400() {
        Query2Resource resource = resourceWithQuery("q5");

        Response resp = resource.addNamedSet("q5", new ThinNamedSet("", "[Measures].Members"));

        assertEquals(400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getEntity();
        assertTrue(body.get("error").toLowerCase().contains("name"));
    }

    @Test
    public void postDuplicateNameReturns400() {
        Query2Resource resource = resourceWithQuery("q6");
        resource.addNamedSet("q6", new ThinNamedSet("Top5", "[Measures].Members"));

        Response resp = resource.addNamedSet("q6", new ThinNamedSet("Top5", "[Measures].AllMembers"));

        assertEquals(400, resp.getStatus());
    }

    @Test
    public void unknownQueryReturns404OnGet() {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ThinQueryService());

        Response resp = resource.getNamedSets("nope");

        assertEquals(404, resp.getStatus());
    }

    @Test
    public void unknownQueryReturns404OnPost() {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ThinQueryService());

        Response resp = resource.addNamedSet("nope", new ThinNamedSet("Top5", "[Measures].Members"));

        assertEquals(404, resp.getStatus());
    }
}
