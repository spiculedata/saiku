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

package org.saiku.web.rest.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.web.rest.objects.resultset.Cell;
import org.saiku.web.rest.objects.resultset.QueryResult;

public class RestUtil {
	
	public static QueryResult convert(ResultSet rs) {
		Integer width = 0;
        Integer height = 0;
        Cell[] header = null;
        ArrayList<Cell[]> rows = new ArrayList<Cell[]>();
        
        // System.out.println("DATASET");
        try {
			while (rs.next()) {
			    if (height == 0) {
			        width = rs.getMetaData().getColumnCount();
			        header = new Cell[width];
			        for (int s = 0; s < width; s++) {
			            header[s] = new Cell(rs.getMetaData().getColumnName(s + 1),Cell.Type.COLUMN_HEADER);
			        }
			        if (width > 0) {
			            rows.add(header);
			            // System.out.println(" |");
			        }
			    }
			    Cell[] row = new Cell[width];
			    for (int i = 0; i < width; i++) {
			    	String content = rs.getString(i + 1);
			        
			        if (content == null)
			            content = "";
			        row[i] = new Cell(content, Cell.Type.DATA_CELL);
			    }
			    rows.add(row);
			    height++;
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return new QueryResult(rows,0,width,height);
	}
	public static QueryResult convert(CellDataSet cellSet) {
		ArrayList<Cell[]> rows = new ArrayList<Cell[]>();
		if (cellSet == null || cellSet.getCellSetBody() == null || cellSet.getCellSetHeaders() == null) {
			return null;
		}
		AbstractBaseCell[][] body = cellSet.getCellSetBody();
		AbstractBaseCell[][] headers = cellSet.getCellSetHeaders();
		
		
		
		for (AbstractBaseCell header[] : headers) {
			rows.add(convert(header, Cell.Type.COLUMN_HEADER));
		}
		
		for (AbstractBaseCell row[] : body) {
			rows.add(convert(row, Cell.Type.ROW_HEADER));
		}
		QueryResult qr = new QueryResult(rows, cellSet.getRuntime(), cellSet.getWidth(), cellSet.getHeight());
		return qr;
		
	}
	
	public static Cell[] convert(AbstractBaseCell[] acells, Cell.Type headertype) {
		Cell[]  cells = new Cell[acells.length];
		for (int i = 0; i < acells.length; i++) {
			cells[i] = convert(acells[i], headertype);
		}
		return cells;
	}
	
	public static Cell convert(AbstractBaseCell acell, Cell.Type headertype) {
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

}
