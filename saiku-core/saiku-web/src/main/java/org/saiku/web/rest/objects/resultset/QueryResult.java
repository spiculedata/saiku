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
package org.saiku.web.rest.objects.resultset;

import java.util.List;

import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.web.rest.util.RestUtil;

public class QueryResult {
	
	private List<Cell[]> cellset;
    private Total[][] rowTotalsLists;
    private Total[][] colTotalsLists;
	private Integer runtime;
	private String error;
	private Integer height;
	private Integer width;
	private int topOffset;
	private int leftOffset;
	
	public QueryResult(List<Cell[]> cellset, CellDataSet cellDataSet) {
		this(cellset, cellDataSet.getRuntime(), cellDataSet.getWidth(), cellDataSet.getHeight(), cellDataSet.getLeftOffset(), cellDataSet.getTopOffset(), RestUtil.convertTotals(cellDataSet.getRowTotalsLists()), RestUtil.convertTotals(cellDataSet.getColTotalsLists()));
	}
	
	public QueryResult(List<Cell[]> cellset, int runtime, int width, int height, int leftOffset, int topOffset, Total[][] rowTotalsLists, Total[][] colTotalsLists) {
		this(cellset, runtime, width, height);
		this.rowTotalsLists = rowTotalsLists;
		this.colTotalsLists = colTotalsLists;
		this.topOffset = topOffset;
		this.leftOffset = leftOffset;
	}

	public QueryResult(List<Cell[]> cellset, int runtime, int width, int height) {
		this.cellset = cellset;
		this.runtime = runtime;
		this.height = height;
		this.width = width;
	}
	
	public QueryResult(String error) {
		this.error = error;
	}

	public List<Cell[]> getCellset() {
		return cellset;
	}

	public Integer getRuntime() {
		return runtime;
	}

	public String getError() {
		return error;
	}

	public Integer getHeight() {
		return height;
	}

	public Integer getWidth() {
		return width;
	}
	
	public void setRuntime(Integer runtime) {
		this.runtime = runtime;
	}

	public void setHeight(Integer height) {
		this.height = height;
	}

	public void setWidth(Integer width) {
		this.width = width;
	}

	public Total[][] getRowTotalsLists() {
		return rowTotalsLists;
	}

	public void setRowTotalsLists(Total[][] rowTotalsLists) {
		this.rowTotalsLists = rowTotalsLists;
	}
	
	public Total[][] getColTotalsLists() {
		return colTotalsLists;
	}

	public void setColTotalsLists(Total[][] colTotalsLists) {
		this.colTotalsLists = colTotalsLists;
	}

	public int getTopOffset() {
		return topOffset;
	}

	public int getLeftOffset() {
		return leftOffset;
	}

}
