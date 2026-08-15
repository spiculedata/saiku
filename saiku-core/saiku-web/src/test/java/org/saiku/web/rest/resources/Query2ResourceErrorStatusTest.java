/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import mondrian.olap.MemoryLimitExceededException;
import mondrian.olap.MondrianException;
import mondrian.olap.QueryTimeoutException;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.resultset.QueryResult;

/**
 * saiku#1865 — {@code /query/execute} used to answer {@code 200 OK} for every failure, with the
 * message tucked in the envelope's {@code error} field. That is invisible to anything reasoning
 * about transport: proxies, retry middleware, monitoring, and any client that treats 2xx as
 * success.
 *
 * <p>Two properties are locked here, and the second matters as much as the first: the status must
 * now reflect the failure, AND the body must stay a {@link QueryResult} carrying {@code error} —
 * that envelope is what the UI renders inline where the grid would be. A status change that also
 * changed the body would have replaced a readable message with a raw HTTP code.
 */
public class Query2ResourceErrorStatusTest {

    /** A service whose execute always fails with the supplied exception. */
    private static final class ExplodingThinQueryService extends ThinQueryService {
        private final RuntimeException boom;

        ExplodingThinQueryService(RuntimeException boom) {
            this.boom = boom;
        }

        @Override
        public boolean isMdxDrillthrough(ThinQuery tq) {
            return false;
        }

        @Override
        public CellDataSet execute(ThinQuery tq) {
            throw boom;
        }
    }

    private static Response executeFailing(RuntimeException boom) {
        Query2Resource resource = new Query2Resource();
        ThinQuery tq = new ThinQuery();
        tq.setName("status-test");
        tq.setMdx("SELECT FROM [stub]");
        resource.setThinQueryService(new ExplodingThinQueryService(boom));
        return resource.execute(tq, null);
    }

    /**
     * A name the caller got wrong — unknown connection, cube, member — is a 400: the request is
     * fixable by the caller, and the message tells them how.
     */
    @Test
    public void anUnresolvableReferenceIsABadRequest() {
        Response resp = executeFailing(
                new SaikuServiceException("wrapped", new SaikuOlapException("Unknown connection ( nope )")));

        assertEquals(400, resp.getStatus());
    }

    /** The classification walks the cause chain, not just the outermost exception. */
    @Test
    public void theCauseChainIsInspectedNotJustTheTopException() {
        Response resp = executeFailing(new SaikuServiceException(
                "outer", new IllegalStateException("middle", new SaikuOlapException("Unknown cube ( nope )"))));

        assertEquals(400, resp.getStatus());
    }

    /** Anything we cannot attribute to the caller is ours, and reports 500. */
    @Test
    public void anUnattributableFailureIsAServerError() {
        Response resp = executeFailing(new IllegalStateException("connection pool exhausted"));

        assertEquals(500, resp.getStatus());
    }

    /**
     * THE companion property. The status changed; the body must not. Clients — including the
     * Saiku UI's own grid — read {@code error} off a {@link QueryResult}, and a bare status with
     * no envelope would strip the only human-readable explanation out of the response.
     */
    @Test
    public void theFailureBodyIsStillAQueryResultCarryingTheMessage() {
        Response resp = executeFailing(
                new SaikuServiceException("wrapped", new SaikuOlapException("Unknown connection ( nope )")));

        assertNotNull("failure must carry a body", resp.getEntity());
        assertTrue(
                "failure body must still be a QueryResult, not a bare string", resp.getEntity() instanceof QueryResult);
        QueryResult qr = (QueryResult) resp.getEntity();
        assertNotNull("the error message was dropped", qr.getError());
        assertTrue(
                "the message lost the connection name: " + qr.getError(),
                qr.getError().contains("nope"));
    }

    /**
     * A typo'd measure — the commonest query failure there is — never reaches the warehouse, so it
     * is the caller's to fix. Mondrian reports it as a bare MondrianException, which is why the
     * classification cannot stop at SaikuOlapException.
     */
    @Test
    public void anUnresolvableMdxReferenceIsABadRequest() {
        Response resp = executeFailing(
                new MondrianException("Mondrian Error:MDX object '[Measures].[Nope]' not found in cube 'Sales'"));

        assertEquals(400, resp.getStatus());
    }

    /** A warehouse failure surfaced through Mondrian is ours, not the caller's. */
    @Test
    public void aMondrianFailureCausedByTheWarehouseIsAServerError() {
        Response resp = executeFailing(new MondrianException(
                "Mondrian Error:Internal error", new java.sql.SQLException("connection reset by peer")));

        assertEquals(500, resp.getStatus());
    }

    /** Resource limits mean the query was valid and we could not finish it. */
    @Test
    public void aResourceLimitIsAServerErrorNotAClientError() {
        // Both extend ResultLimitExceededException, which is the abstract base the production
        // check keys on — so these two also pin that the base covers the whole family.
        assertEquals(500, executeFailing(new QueryTimeoutException("timed out")).getStatus());
        assertEquals(
                500,
                executeFailing(new MemoryLimitExceededException("cell cache full"))
                        .getStatus());
    }

    /** JSON, so the client can parse the envelope off a non-2xx at all. */
    @Test
    public void theFailureIsStillDeclaredAsJson() {
        Response resp = executeFailing(new IllegalStateException("boom"));

        assertNotNull("media type must be set", resp.getMediaType());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
    }
}
