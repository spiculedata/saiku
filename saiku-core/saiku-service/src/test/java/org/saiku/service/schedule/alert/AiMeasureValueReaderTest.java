/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;

/** Scalar-extraction + off-request wiring tests for {@link AiMeasureValueReader} (saiku#1098). */
public class AiMeasureValueReaderTest {

    private static CellDataSet oneCell(DataCell cell) {
        CellDataSet cds = new CellDataSet(1, 1);
        cds.setCellSetBody(new AbstractBaseCell[][] {{cell}});
        return cds;
    }

    private static CellDataSet oneCell(double value) {
        DataCell cell = new DataCell();
        cell.setRawNumber(value);
        return oneCell(cell);
    }

    // ---------------- scalar extraction ----------------

    @Test
    public void extractsRawNumber() throws Exception {
        DataCell cell = new DataCell();
        cell.setRawNumber(1234.5);
        assertEquals(1234.5, AiMeasureValueReader.extractScalar(oneCell(cell), "Unit Sales"), 0.0001);
    }

    @Test
    public void fallsBackToParsingRawValue() throws Exception {
        DataCell cell = new DataCell();
        cell.setRawValue("42.0");
        assertEquals(42.0, AiMeasureValueReader.extractScalar(oneCell(cell), "Unit Sales"), 0.0001);
    }

    @Test(expected = AlertQueryException.class)
    public void nullResultThrows() throws Exception {
        AiMeasureValueReader.extractScalar(null, "Unit Sales");
    }

    @Test(expected = AlertQueryException.class)
    public void emptyBodyThrows() throws Exception {
        CellDataSet cds = new CellDataSet(1, 1);
        cds.setCellSetBody(new AbstractBaseCell[][] {});
        AiMeasureValueReader.extractScalar(cds, "Unit Sales");
    }

    @Test(expected = AlertQueryException.class)
    public void nonNumericCellThrows() throws Exception {
        DataCell cell = new DataCell();
        cell.setRawValue("N/A");
        AiMeasureValueReader.extractScalar(oneCell(cell), "Unit Sales");
    }

    // ---------------- ctor guards ----------------

    @Test(expected = IllegalArgumentException.class)
    public void ctorRejectsNullDiscover() {
        // Production ctor: olapDiscoverService is required (no session-scoped bean anymore).
        new AiMeasureValueReader(schemaService(), null, null, null, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void factoryCtorRejectsNulls() {
        new AiMeasureValueReader(null, (AiMeasureValueReader.ThinQueryServiceFactory) null);
    }

    // ---------------- off-request wiring (the missing test) ----------------

    /**
     * The bug this guards: the reader must NOT depend on a session/request-scoped proxy, so calling it
     * from a plain background thread with NO bound request/session scope must NOT throw a scope /
     * {@code IllegalStateException} — and the query executor must actually be invoked. A fresh
     * {@link ThinQueryService} is minted per run by the injected factory (mirroring how Spring's
     * singleton collaborators are wired), and here that factory hands back a fake executor.
     */
    @Test
    public void readMeasureRunsOffRequestThreadWithoutScopeError() throws Exception {
        final AtomicBoolean executed = new AtomicBoolean(false);

        // A fake executor standing in for the per-run ThinQueryService — no Mondrian, no request scope.
        ThinQueryService fakeExecutor = new ThinQueryService() {
            @Override
            public CellDataSet execute(ThinQuery tq) {
                executed.set(true);
                return oneCell(4242.0);
            }
        };

        AiMeasureValueReader reader = new AiMeasureValueReader(schemaService(), () -> fakeExecutor);

        AiCubeRef cube = new AiCubeRef("conn", "cat", "schema", "Sales");

        // Run on a plain ExecutorService thread — NO Spring request/session bound to this thread.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Double> task = () -> reader.readMeasure(cube, "Unit Sales", List.of());
            Future<Double> future = pool.submit(task);
            double value = future.get();
            assertTrue("the query executor must be invoked off-request", executed.get());
            assertEquals(4242.0, value, 0.0001);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- helpers ----------------

    /** A minimal cube-metadata service returning a one-measure schema so the converter succeeds. */
    private static AiCubeMetadataService schemaService() {
        return ref -> {
            AiSchema schema = new AiSchema("conn/cat/schema/Sales", "Sales", "[Sales]");
            schema.measures.put(
                    AiSchema.key("Unit Sales"), new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));
            return schema;
        };
    }
}
