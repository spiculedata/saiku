/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;
import org.saiku.olap.query2.ThinNamedSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * saiku#824 — named-sets management on {@link ThinQueryService}'s in-memory query context.
 * Validates the same shape {@code AiSchemaConverter#validateNamedSets} enforces for the AI
 * Query API's inline {@code namedSets[]}: non-blank {@code name}/{@code expression}, unique
 * {@code name} per query.
 */
public class ThinQueryServiceNamedSetsTest {

    @Test
    public void addNamedSetAppendsToTheModel() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q1");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);

        ThinNamedSet added = svc.addNamedSet("q1", new ThinNamedSet("Top5", "Head([Product].Members, 5)"));

        assertSame(added, tq.getQueryModel().getNamedSets().get(0));
        assertEquals(1, tq.getQueryModel().getNamedSets().size());
        assertEquals("Top5", tq.getQueryModel().getNamedSets().get(0).getName());
    }

    @Test
    public void getNamedSetsReturnsTheLiveList() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q2");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);

        svc.addNamedSet("q2", new ThinNamedSet("A", "[Measures].Members"));
        svc.addNamedSet("q2", new ThinNamedSet("B", "[Measures].Members"));

        List<ThinNamedSet> sets = svc.getNamedSets("q2");
        assertEquals(2, sets.size());
        assertEquals("A", sets.get(0).getName());
        assertEquals("B", sets.get(1).getName());
    }

    @Test
    public void removeNamedSetDeletesByName() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q3");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);
        svc.addNamedSet("q3", new ThinNamedSet("Keep", "[Measures].Members"));
        svc.addNamedSet("q3", new ThinNamedSet("Drop", "[Measures].Members"));

        svc.removeNamedSet("q3", "Drop");

        List<ThinNamedSet> sets = svc.getNamedSets("q3");
        assertEquals(1, sets.size());
        assertEquals("Keep", sets.get(0).getName());
    }

    @Test
    public void removeUnknownNamedSetThrows() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q4");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);

        try {
            svc.removeNamedSet("q4", "Nope");
            fail("expected SaikuServiceException");
        } catch (SaikuServiceException e) {
            assertTrue(e.getMessage().contains("Nope"));
        }
    }

    @Test
    public void addNamedSetRejectsBlankName() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q5");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);

        try {
            svc.addNamedSet("q5", new ThinNamedSet(" ", "[Measures].Members"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("name"));
        }
        assertTrue(tq.getQueryModel().getNamedSets().isEmpty());
    }

    @Test
    public void addNamedSetRejectsBlankExpression() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q6");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);

        try {
            svc.addNamedSet("q6", new ThinNamedSet("Top5", ""));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("expression"));
        }
    }

    @Test
    public void addNamedSetRejectsDuplicateName() {
        ThinQueryService svc = new ThinQueryService();
        ThinQuery tq = new ThinQuery();
        tq.setName("q7");
        tq.setQueryModel(new ThinQueryModel());
        svc.registerExternalContext(tq, null);
        svc.addNamedSet("q7", new ThinNamedSet("Top5", "[Measures].Members"));

        try {
            svc.addNamedSet("q7", new ThinNamedSet("Top5", "[Measures].AllMembers"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Duplicate"));
        }
        assertEquals(1, tq.getQueryModel().getNamedSets().size());
    }

    @Test
    public void unknownQueryNameThrowsOnAdd() {
        ThinQueryService svc = new ThinQueryService();
        try {
            svc.addNamedSet("missing", new ThinNamedSet("Top5", "[Measures].Members"));
            fail("expected SaikuServiceException");
        } catch (SaikuServiceException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void unknownQueryNameThrowsOnGet() {
        ThinQueryService svc = new ThinQueryService();
        try {
            svc.getNamedSets("missing");
            fail("expected SaikuServiceException");
        } catch (SaikuServiceException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void namedSetIsLazilyAttachedWhenQueryModelIsNull() {
        ThinQueryService svc = new ThinQueryService();
        // MDX-constructor path leaves queryModel null.
        ThinQuery tq = new ThinQuery("q8", null, "SELECT FROM [Sales]");
        svc.registerExternalContext(tq, null);
        assertTrue("MDX constructor leaves queryModel null", tq.getQueryModel() == null);

        svc.addNamedSet("q8", new ThinNamedSet("Top5", "[Measures].Members"));

        assertEquals(1, tq.getQueryModel().getNamedSets().size());
    }
}
