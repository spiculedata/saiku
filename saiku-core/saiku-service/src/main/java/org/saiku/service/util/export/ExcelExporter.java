/*
 * Copyright (C) 2011 OSBI Ltd
 *
 * This program is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU General Public License as published by the Free 
 * Software Foundation; either version 2 of the License, or (at your option) 
 * any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 *
 */
package org.saiku.service.util.export;

import org.olap4j.CellSet;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.util.export.excel.ExcelWorksheetBuilder;

import java.util.List;

public class ExcelExporter {

	public static byte[] exportExcel(CellSet cellSet, List<SaikuDimensionSelection> filters) {
		return exportExcel(cellSet, new HierarchicalCellSetFormatter(), filters);
	}

	public static byte[] exportExcel(CellSet cellSet,
                                     ICellSetFormatter formatter,
                                     List<SaikuDimensionSelection> filters) {
		CellDataSet table = OlapResultSetUtil.cellSet2Matrix(cellSet, formatter);
		return getExcel(table, filters);
	}

	private static byte[] getExcel(CellDataSet table, List<SaikuDimensionSelection> filters) {
        // TBD Sheet name is parametric. Useful for future ideas or improvements
        ExcelWorksheetBuilder worksheetBuilder = new ExcelWorksheetBuilder(table, filters, "Sheet 1");
        return worksheetBuilder.build();
	}
}
