/*
 *   Copyright 2012 OSBI Ltd
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
package org.saiku.olap.util;

import org.olap4j.CellSet;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;

public class OlapResultSetUtil {

    public static CellDataSet cellSet2Matrix(final CellSet cellSet) {
        final ICellSetFormatter formatter = new HierarchicalCellSetFormatter();
        return cellSet2Matrix(cellSet, formatter);
    }

    public static CellDataSet cellSet2Matrix(final CellSet cellSet, ICellSetFormatter formatter) {
        if (cellSet == null) {
            return new CellDataSet(0, 0);
        }

        final Matrix matrix = formatter.format(cellSet);
        final CellDataSet cds = new CellDataSet(matrix.getMatrixWidth(), matrix.getMatrixHeight());

        int z = 0;
        final AbstractBaseCell[][] bodyvalues =
                new AbstractBaseCell[matrix.getMatrixHeight() - matrix.getOffset()][matrix.getMatrixWidth()];

        for (int y = matrix.getOffset(); y < matrix.getMatrixHeight(); y++) {
            for (int x = 0; x < matrix.getMatrixWidth(); x++) {
                bodyvalues[z][x] = matrix.get(x, y);
            }

            z++;
        }

        cds.setCellSetBody(bodyvalues);

        final AbstractBaseCell[][] headervalues = new AbstractBaseCell[matrix.getOffset()][matrix.getMatrixWidth()];

        for (int y = 0; y < matrix.getOffset(); y++) {
            for (int x = 0; x < matrix.getMatrixWidth(); x++) {
                headervalues[y][x] = matrix.get(x, y);
            }
        }

        cds.setCellSetHeaders(headervalues);
        cds.setOffset(matrix.getOffset());

        return cds;
    }
}
