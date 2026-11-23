/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.async.AsyncQueryHandle;
import org.saiku.service.async.AsyncQueryService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.springframework.web.context.request.RequestAttributes;

/**
 * saiku#1973 — an access-denied datasource (the saiku#1968 fail-closed denial) is an authorization
 * decision, and every REST door must say so the same way: HTTP 403, an envelope the client can
 * parse, and a body that says nothing about WHICH datasource was denied.
 *
 * <p>Pre-fix this leaked three different shapes for one denial: a 500 echoing
 * {@code SaikuServiceException: Access denied: ... datasource "X".} from {@code /query/execute}, a
 * bare 500 from the async submit path, and an opaque 500 from the global mapper. None of them were
 * 403.
 */
public class Query2ResourceAccessDeniedTest {

    /** The literal denial {@code SecurityAwareConnectionManager} throws — it names the datasource. */
    private static SaikuAccessDeniedException denial() {
        return new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"sales_prod\".");
    }

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

    /** An async service whose submit always fails with the supplied exception. */
    private static final class ExplodingAsyncQueryService extends AsyncQueryService {
        private final RuntimeException boom;

        ExplodingAsyncQueryService(RuntimeException boom) {
            this.boom = boom;
        }

        @Override
        public AsyncQueryHandle submit(ThinQuery query, RequestAttributes requestAttributes) {
            throw boom;
        }
    }

    private static Query2Resource resource(RuntimeException boom) {
        Query2Resource resource = new Query2Resource();
        resource.setThinQueryService(new ExplodingThinQueryService(boom));
        return resource;
    }

    private static ThinQuery thinQuery() {
        ThinQuery tq = new ThinQuery();
        tq.setName("access-denied-test");
        tq.setMdx("SELECT FROM [stub]");
        return tq;
    }

    @Test
    public void aDeniedDatasourceIsForbiddenNotInternalError() {
        Response resp = resource(denial()).execute(thinQuery(), null);

        assertEquals(403, resp.getStatus());
    }

    @Test
    public void theDenialIsStillAQueryResultSoTheGridCanRenderIt() {
        Response resp = resource(denial()).execute(thinQuery(), null);

        assertNotNull("a denial must carry a body", resp.getEntity());
        assertTrue("denial body must be a QueryResult, not a bare string", resp.getEntity() instanceof QueryResult);
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());
    }

    @Test
    public void theDenialBodyDoesNotNameTheDatasource() {
        Response resp = resource(denial()).execute(thinQuery(), null);

        QueryResult qr = (QueryResult) resp.getEntity();
        assertNotNull("a denial must say something", qr.getError());
        assertFalse("datasource name leaked: " + qr.getError(), qr.getError().contains("sales_prod"));
        assertFalse(
                "datasource denial text leaked: " + qr.getError(),
                qr.getError().contains("Access denied: your account"));
    }

    /** Classification is by TYPE in the cause chain, so a rewrapped denial still reads as a denial. */
    @Test
    public void aRewrappedDenialIsStillForbidden() {
        Response resp = resource(new SaikuServiceException("outer", new IllegalStateException("middle", denial())))
                .execute(thinQuery(), null);

        assertEquals(403, resp.getStatus());
        QueryResult qr = (QueryResult) resp.getEntity();
        assertFalse("datasource name leaked: " + qr.getError(), qr.getError().contains("sales_prod"));
    }

    /** The async submit door used to answer 500 with the raw root cause; it must match /execute. */
    @Test
    public void theAsyncSubmitDoorAnswersTheSameWay() {
        Query2Resource resource = resource(denial());
        resource.setAsyncQueryService(new ExplodingAsyncQueryService(denial()));

        Response resp = resource.executeAsync(thinQuery());

        assertEquals(403, resp.getStatus());
        QueryResult qr = (QueryResult) resp.getEntity();
        assertFalse("datasource name leaked: " + qr.getError(), qr.getError().contains("sales_prod"));
    }

    /** Nothing else moved: an unresolvable reference is still a 400 with a helpful message. */
    @Test
    public void anUnresolvableReferenceIsStillABadRequest() {
        Response resp = resource(new SaikuServiceException(
                        "wrapped", new org.saiku.olap.util.exception.SaikuOlapException("Unknown connection ( nope )")))
                .execute(thinQuery(), null);

        assertEquals(400, resp.getStatus());
        QueryResult qr = (QueryResult) resp.getEntity();
        assertTrue(
                "client-fixable errors must keep their message: " + qr.getError(),
                qr.getError().contains("nope"));
    }

    /** ...and a genuine internal fault is still a 500. */
    @Test
    public void anUnrelatedFailureIsStillAServerError() {
        assertEquals(
                500,
                resource(new IllegalStateException("connection pool exhausted"))
                        .execute(thinQuery(), null)
                        .getStatus());
    }
}
