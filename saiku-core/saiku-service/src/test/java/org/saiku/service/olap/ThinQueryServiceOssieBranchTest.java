/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Verifies the queryType branch in {@link ThinQueryService#execute(ThinQuery)}:
 *
 * <ul>
 *   <li>{@code queryType="OSSIE"} → dispatches to the injected OssieQueryService and returns
 *       the CellDataSet it produces. The MDX-side {@code olapDiscoverService.getNativeConnection}
 *       is never touched (which would explode on an OSSIE connection).
 *   <li>{@code queryType="OSSIE"} + no OssieQueryService wired → clean SaikuServiceException.
 * </ul>
 *
 * Doesn't cover the OLAP branch — that's exercised by every existing Mondrian-flavoured
 * test in the suite. This test only proves the discriminator works.
 */
public class ThinQueryServiceOssieBranchTest {

    private ThinQueryService svc;
    private RecordingOssieQueryService ossie;

    @Before
    public void setUp() {
        svc = new ThinQueryService();
        ossie = new RecordingOssieQueryService();
    }

    @Test
    public void ossieBranchDispatchesToOssieQueryService() {
        svc.setOssieQueryService(ossie);
        ThinQuery tq = new ThinQuery();
        tq.setName("smoke");
        tq.setQueryType("OSSIE");
        OssieQueryModel qm = new OssieQueryModel();
        qm.setConnection("SALES");
        qm.setModel("SALES");
        qm.setFactDataset("orders");
        tq.setOssieQueryModel(qm);

        CellDataSet result = svc.execute(tq);
        assertNotNull(result);
        assertSame("must return the exact CellDataSet the Ossie service produced", ossie.stubResult, result);
        assertSame("must pass through the caller's ThinQuery unchanged", tq, ossie.lastExecuted);
    }

    @Test
    public void ossieBranchCaseInsensitive() {
        // The dispatch match is caller-supplied text — accept mixed case so a lowercase
        // wire value from an older client doesn't fall through to the MDX branch (which
        // would fail hard with a null olapDiscoverService).
        svc.setOssieQueryService(ossie);
        ThinQuery tq = new ThinQuery();
        tq.setName("smoke");
        tq.setQueryType("ossie");
        OssieQueryModel qm = new OssieQueryModel();
        qm.setConnection("SALES");
        qm.setModel("SALES");
        qm.setFactDataset("orders");
        tq.setOssieQueryModel(qm);

        CellDataSet result = svc.execute(tq);
        assertNotNull(result);
        assertSame(ossie.lastExecuted, tq);
    }

    @Test
    public void missingOssieServiceRaisesActionableException() {
        // OssieQueryService not wired — this can happen in deployments where Spring
        // wiring drops the bean. The dispatch branch must fail loudly rather than
        // fall through to the MDX path.
        // svc.setOssieQueryService(...) NOT called.
        ThinQuery tq = new ThinQuery();
        tq.setName("smoke");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(new OssieQueryModel());
        try {
            svc.execute(tq);
            fail("expected SaikuServiceException");
        } catch (SaikuServiceException e) {
            assertNotNull(e.getMessage());
            assert e.getMessage().toLowerCase().contains("ossie");
        }
    }

    @Test
    public void ossieServiceExceptionsPropagateWrapped() {
        // If the underlying service throws, ThinQueryService wraps it in a
        // SaikuServiceException so callers don't have to unwrap-and-catch multiple types.
        RecordingOssieQueryService throwing = new RecordingOssieQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                throw new RuntimeException("boom");
            }
        };
        svc.setOssieQueryService(throwing);
        ThinQuery tq = new ThinQuery();
        tq.setName("smoke");
        tq.setQueryType("OSSIE");
        tq.setOssieQueryModel(new OssieQueryModel());
        try {
            svc.execute(tq);
            fail("expected wrapped exception");
        } catch (SaikuServiceException e) {
            assertNotNull(e.getCause());
            assert "boom".equals(e.getCause().getMessage());
        }
    }

    /** Non-network test double for OssieQueryService. Returns a canned CellDataSet. */
    private static class RecordingOssieQueryService extends OssieQueryService {
        final CellDataSet stubResult = new CellDataSet(0, 0);
        ThinQuery lastExecuted;

        @Override
        public CellDataSet execute(ThinQuery tq) {
            this.lastExecuted = tq;
            return stubResult;
        }
    }
}
