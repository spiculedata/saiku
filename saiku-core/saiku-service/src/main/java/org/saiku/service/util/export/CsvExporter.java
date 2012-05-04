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

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.olap4j.CellSet;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.formatter.CellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.KeyValue;

public class CsvExporter {
	
	public static byte[] exportCsv(CellSet cellSet) {
		return exportCsv(cellSet,",","\"");
	}
	
	public static byte[] exportCsv(CellSet cellSet, String delimiter, String enclosing) {
		return exportCsv(cellSet, delimiter, enclosing, new CellSetFormatter());
	}

	public static byte[] exportCsv(CellSet cellSet, String delimiter, String enclosing, ICellSetFormatter formatter) {
		CellDataSet table = OlapResultSetUtil.cellSet2Matrix(cellSet, formatter);
		return getCsv(table, delimiter, enclosing);
	}
	
	public static byte[] exportCsv(ResultSet rs) { 
		return getCsv(rs,",","\"", true, null);
	}
	
	public static byte[] exportCsv(ResultSet rs, String delimiter, String enclosing) {
		return getCsv(rs, delimiter, enclosing, true, null);
	}
	
	public static byte[] exportCsv(ResultSet rs, String delimiter, String enclosing, boolean printHeader, List<KeyValue<String,String>> additionalColumns) {
		return getCsv(rs, delimiter, enclosing, printHeader, additionalColumns);
	}

	private static byte[] getCsv(ResultSet rs, String delimiter, String enclosing, boolean printHeader, List<KeyValue<String,String>> additionalColumns) {
		Integer width = 0;
		
        Integer height = 0;
        StringBuilder sb = new StringBuilder();
        String addCols = null;
        try {
			while (rs.next()) {
			    if (height == 0) {
			        width = rs.getMetaData().getColumnCount();
			        String header = null;
			        if (additionalColumns != null) {
			        	for (KeyValue<String,String> kv : additionalColumns) {
			        		if (header == null) {
			        			header = "";
			        			addCols ="";
			        		} else {
				            	header += delimiter;
			        		}
			        		header += enclosing + kv.getKey() + enclosing;
			        		addCols += enclosing + kv.getValue() + enclosing + delimiter;
			        	}
			        }
			        for (int s = 0; s < width; s++) {
			            if (header != null) {
			            	header += delimiter;
			            } else {
			            	header = "";
			            }
			            header += enclosing + rs.getMetaData().getColumnName(s + 1) + enclosing;
			        }
			        if (header != null && printHeader) {
			        	header += "\r\n";
			        	sb.append(header);
			        }
			    }
			    if (addCols != null) {
			    	sb.append(addCols);
			    }
			    for (int i = 0; i < width; i++) {
			    	String content = rs.getString(i + 1);
			        if (content == null)
			            content = "";
			        if (i > 0) {
			        	sb.append(delimiter);
			        }
			        sb.append(enclosing + content + enclosing);
			    }
			    sb.append("\r\n");
			    height++;
			}
			return sb.toString().getBytes("UTF8"); //$NON-NLS-1$
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
		return new byte[0];
	}

	private static byte[] getCsv(CellDataSet table, String delimiter, String enclosing) {
		if (table != null) {
			AbstractBaseCell[][] rowData = table.getCellSetBody();
			AbstractBaseCell[][] rowHeader = table.getCellSetHeaders();

			String[][] result = new String[rowHeader.length + rowData.length][];
			for (int x = 0; x<rowHeader.length;x++) {
				List<String> cols = new ArrayList<String>();
				for(int y = 0; y < rowHeader[x].length;y++) {
					String value = rowHeader[x][y].getFormattedValue();
					if(value == null || value == "null")  //$NON-NLS-1$
						value=""; //$NON-NLS-1$
					cols.add(enclosing + value + enclosing); 
				}
				result[x]= cols.toArray(new String[cols.size()]);

			}
			for (int x = 0; x<rowData.length ;x++) {
				int xTarget = rowHeader.length + x;
				List<String> cols = new ArrayList<String>();
				for(int y = 0; y < rowData[x].length;y++) {
					String value = rowData[x][y].getFormattedValue();
					if (rowData[x][y] instanceof DataCell && ((DataCell) rowData[x][y]).getRawNumber() != null ) {
						value = ((DataCell) rowData[x][y]).getRawNumber().toString();
					}

					if(value == null || value == "null")  {
						value="";
					}
					value = enclosing + value + enclosing;
					cols.add(value); 
				}
				result[xTarget]= cols.toArray(new String[cols.size()]);

			}
			return export(result, delimiter);
		}
		return new byte[0];
	}

	private static byte[] export(String[][] resultSet, String delimiter) {
		try {
			String output = "";
            StringBuffer buf = new StringBuffer();
			if(resultSet.length > 0){
				for(int i =  0; i < resultSet.length; i++){
					String[] vs = resultSet[i];

					for(int j = 0; j < vs.length ; j++){
						String value = vs[j];
						
						if ( j > 0) {
						    buf.append(delimiter + value);
							//output += delimiter + value;
						}
						else {
						    buf.append(value);
							//output += value;
						}
					}
					buf.append("\r\n");
					//output += "\r\n"; //$NON-NLS-1$
				}
				output = buf.toString();
				return output.getBytes("UTF8"); //$NON-NLS-1$
			}
		} catch (Throwable e) {
			throw new SaikuServiceException("Error creating csv export for query"); //$NON-NLS-1$
		}
		return new byte[0];
	}
}
