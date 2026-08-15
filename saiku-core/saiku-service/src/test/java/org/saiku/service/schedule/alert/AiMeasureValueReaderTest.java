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

import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;

/** Scalar-extraction tests for {@link AiMeasureValueReader} (saiku#1098). */
public class AiMeasureValueReaderTest {

    private static CellDataSet oneCell(DataCell cell) {
        CellDataSet cds = new CellDataSet(1, 1);
        cds.setCellSetBody(new AbstractBaseCell[][] {{cell}});
        return cds;
    }

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

    @Test(expected = IllegalArgumentException.class)
    public void ctorRejectsNulls() {
        new AiMeasureValueReader(null, null);
    }
}
