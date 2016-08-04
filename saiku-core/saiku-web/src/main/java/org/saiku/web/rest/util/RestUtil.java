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

package org.saiku.web.rest.util;


import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.service.olap.drillthrough.DrillThroughResult;
import org.saiku.service.olap.totals.TotalNode;
import org.saiku.service.util.export.ResultSetHelper;
import org.saiku.web.rest.objects.resultset.Cell;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.saiku.web.rest.objects.resultset.Total;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public class RestUtil {
    private static final Logger log = LoggerFactory.getLogger(RestUtil.class);

	public static QueryResult convert(ResultSet rs) throws Exception {
		return convert(rs, 0);
	}

	private static QueryResult convert(ResultSet rs, int limit) throws Exception {

		Integer width = 0;
        Integer height = 0;
        ResultSetHelper rsch = new ResultSetHelper();
        Cell[] header = null;
        ArrayList<Cell[]> rows = new ArrayList<>();
        
        try {
			while (rs.next() && (limit == 0 || height < limit)) {
			    if (height == 0) {
			        width = rs.getMetaData().getColumnCount();
			        header = new Cell[width];
			        for (int s = 0; s < width; s++) {
			            header[s] = new Cell(rs.getMetaData().getColumnName(s + 1),Cell.Type.COLUMN_HEADER);
			        }
			        if (width > 0) {
			            rows.add(header);
			        }
			    }
			    Cell[] row = new Cell[width];
			    for (int i = 0; i < width; i++) {
			    	int colType = rs.getMetaData().getColumnType(i + 1);
			    	String content = rsch.getValue(rs, colType, i + 1);
			        if (content == null)
			            content = "";
			        row[i] = new Cell(content, Cell.Type.DATA_CELL);
			    }
			    rows.add(row);
			    height++;
			}
		} catch (SQLException e) {
			log.error("SQL Exception", e);
		}
		
		return new QueryResult(rows,0,width,height);
	}
	
	public static QueryResult convert(CellDataSet cellSet) {
		return convert(cellSet, 0);
	}
	
	public static Total[][] convertTotals(List<TotalNode>[] totalLists) {
		if (null == totalLists)
			return null;
		Total[][] retVal = new Total[totalLists.length][];
		for (int i = 0; i < totalLists.length; i++) {
			List<TotalNode> current = totalLists [i];
			retVal[i] = new Total[current.size()];
			for (int j = 0; j < current.size(); j++)
				retVal[i][j] = new Total(current.get(j));
		}
		return retVal;
	}
	
	public static QueryResult convert(CellDataSet cellSet, int limit) {
		ArrayList<Cell[]> rows = new ArrayList<>();
		if (cellSet == null || cellSet.getCellSetBody() == null || cellSet.getCellSetHeaders() == null) {
			return null;
		}
		AbstractBaseCell[][] body = cellSet.getCellSetBody();
		AbstractBaseCell[][] headers = cellSet.getCellSetHeaders();
		
		
		
		for (AbstractBaseCell header[] : headers) {
			rows.add(convert(header, Cell.Type.COLUMN_HEADER));
		}
		for (int i = 0; i < body.length && (limit == 0 || i < limit) ; i++) {
			AbstractBaseCell[] row = body[i];
			rows.add(convert(row, Cell.Type.ROW_HEADER));
		}

	  return new QueryResult(rows, cellSet);
		
	}
	
	private static Cell[] convert(AbstractBaseCell[] acells, Cell.Type headertype) {
		Cell[]  cells = new Cell[acells.length];
		for (int i = 0; i < acells.length; i++) {
			cells[i] = convert(acells[i], headertype);
		}
		return cells;
	}
	
	private static Cell convert(AbstractBaseCell acell, Cell.Type headertype) {
		if (acell != null) {
			if (acell instanceof DataCell) {
				DataCell dcell = (DataCell) acell;
				Properties metaprops = new Properties();
				// metaprops.put("color", "" + dcell.getColorValue());
				String position = null;
				for (Integer number : dcell.getCoordinates()) {
					if (position != null) {
						position += ":" + number.toString();
					}
					else {
						position = number.toString();
					}
				}
				if (position != null) {
					metaprops.put("position", position);
				}
				
				if (dcell != null && dcell.getRawNumber() != null) {
					metaprops.put("raw", "" + dcell.getRawNumber());
				}
				
				
				metaprops.putAll(dcell.getProperties());
				
				// TODO no properties  (NULL) for now - 
				return new Cell(dcell.getFormattedValue(), metaprops, Cell.Type.DATA_CELL);
			}
			if (acell instanceof MemberCell) {
				MemberCell mcell = (MemberCell) acell;
//				Properties metaprops = new Properties();
//				metaprops.put("children", "" + mcell.getChildMemberCount());
//				metaprops.put("uniqueName", "" + mcell.getUniqueName());

				Properties props = new Properties();
				if ( mcell != null) {
					if (mcell.getParentDimension() != null) {
						props.put("dimension", mcell.getParentDimension());
					}
					if (mcell.getUniqueName() != null) {
						props.put("uniquename", mcell.getUniqueName());
					}
					if (mcell.getHierarchy() != null) {
						props.put("hierarchy", mcell.getHierarchy());
					}
					if (mcell.getLevel() != null) {
						props.put("level", mcell.getLevel());
					}
				}
//				props.putAll(mcell.getProperties());

				// TODO no properties  (NULL) for now - 
				if ("row_header_header".equals(mcell.getProperty("__headertype"))) {
					headertype = Cell.Type.ROW_HEADER_HEADER;
				}
				return new Cell("" + mcell.getFormattedValue(), props, headertype);
			}

		}
		return null;
	}

	public static QueryResult convert(DrillThroughResult drillthrough) throws IOException {
		
        Integer height = 0;
        ResultSetHelper rsch = new ResultSetHelper();
        ArrayList<Cell[]> rows = new ArrayList<>();
        AbstractBaseCell[][] cellHeaders = drillthrough.getCellHeaders();
        String[] simpleHeaders = drillthrough.getSimpleHeaders();
        ResultSet rs = drillthrough.getResultSet();
        Integer width = 0;
        
        try {
        	width = rs.getMetaData().getColumnCount();
			if (Objects.nonNull(cellHeaders)) {
        		for (AbstractBaseCell headerRow[] : cellHeaders) {
        			rows.add(convert(headerRow, Cell.Type.COLUMN_HEADER));
        		}
        	} else {
        		Cell[] headerRow = new Cell[width];
        		for (int s = 0; s < width; s++) {
		            headerRow[s] = new Cell(simpleHeaders[s], Cell.Type.COLUMN_HEADER);
		        }
        		rows.add(headerRow);
        	}
			while (rs.next()) {
			    Cell[] row = new Cell[width];
			    for (int i = 0; i < width; i++) {
			    	int colType = rs.getMetaData().getColumnType(i + 1);
			    	String content = rsch.getValue(rs, colType, i + 1);
			        if (content == null)
			            content = "";
			        row[i] = new Cell(content, Cell.Type.DATA_CELL);
			    }
			    rows.add(row);
			    height++;
			}
		} catch (SQLException e) {
			log.error("SQL Exception", e);
		}
		
		return new QueryResult(rows,0,width,height);
	}

}
