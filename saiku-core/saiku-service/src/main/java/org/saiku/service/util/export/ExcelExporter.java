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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import jxl.Workbook;
import jxl.format.Alignment;
import jxl.format.Border;
import jxl.format.BorderLineStyle;
import jxl.format.Colour;
import jxl.write.Label;
import jxl.write.NumberFormat;
import jxl.write.WritableCellFormat;
import jxl.write.WritableFont;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;
import jxl.write.WriteException;

import org.olap4j.CellSet;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.util.OlapResultSetUtil;
import org.saiku.olap.util.formatter.HierarchicalCellSetFormatter;
import org.saiku.olap.util.formatter.ICellSetFormatter;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.excel.ExcelWorksheetBuilder;

public class ExcelExporter {

	public static byte[] exportExcel(CellSet cellSet) {
		return exportExcel(cellSet, new HierarchicalCellSetFormatter());
	}

	public static byte[] exportExcel(CellSet cellSet, ICellSetFormatter formatter) {
		CellDataSet table = OlapResultSetUtil.cellSet2Matrix(cellSet, formatter);
		return getExcel(table);
	}

	private static byte[] getExcel(CellDataSet table) {

/*		if (table != null) {
			AbstractBaseCell[][] rowData = table.getCellSetBody();
			AbstractBaseCell[][] rowHeader = table.getCellSetHeaders();

            String[][] result = new String[rowHeader.length + rowData.length][];
			for (int x = 0; x<rowHeader.length;x++) {
				List<String> cols = new ArrayList<String>();
				for(int y = 0; y < rowHeader[x].length;y++) {
					cols.add(rowHeader[x][y].getFormattedValue()); 
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
					cols.add(value);
				}
				result[xTarget]= cols.toArray(new String[cols.size()]);

			}
			return export(result);
		}
		return new byte[0];
		*/

        ExcelWorksheetBuilder worksheetBuilder = new ExcelWorksheetBuilder(table, "Foglio 1");
        return worksheetBuilder.build();
	}

    private static byte[] export(String[][] resultSet) {
		WritableWorkbook  wb = null;
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			wb = Workbook.createWorkbook(bout);
			wb.setColourRGB(Colour.BLUE, 0xf0,0xf8,0xff);
			wb.setColourRGB(Colour.PALE_BLUE, 0xf9,0xf9,0xf9);
			WritableSheet sheet = wb.createSheet("Sheet", 0); //$NON-NLS-1$

			WritableCellFormat cf;
			int[] columnwidth = new int[resultSet[0].length];
			if(resultSet.length > 0){
				for(int i =  0; i < resultSet.length; i++){
					String[] vs = resultSet[i];
					for(int j = 0; j < vs.length ; j++){
						String value = vs[j];
						if(value == null || value == "null")  //$NON-NLS-1$
							value=""; //$NON-NLS-1$

						if (columnwidth[j] < value.length()) {
							columnwidth[j] = value.length();
						}
						if(resultSet[0][j] == null || resultSet[0][j] == ""){
							cf = (i % 2 != 0 ? getEvenFormat(Alignment.LEFT) : getOddFormat(Alignment.LEFT));
						}					
						else {
							cf = (i % 2 != 0 ? getEvenFormat(Alignment.RIGHT) : getOddFormat(Alignment.RIGHT));

							try {
								Double v = Double.parseDouble(value); 
								WritableCellFormat nf = getNumberFormat();
								nf.setBackground(cf.getBackgroundColour());
								jxl.write.Number n = new jxl.write.Number(j, i , v , nf);
								sheet.addCell(n);
								continue;
							} catch (Exception e) {	}
						}
						Label label = new Label(j,i,value,cf);
						sheet.addCell(label);
					}
				}
				for(int k = 0;k<columnwidth.length;k++) {
					sheet.setColumnView(k, (int) (columnwidth[k]*1.4));
				}
				sheet.insertColumn(0);
				sheet.insertRow(0);

				wb.write();
				wb.close();
				byte[] output =bout.toByteArray();
				return output;

			}
		} catch (Throwable e) {
			throw new SaikuServiceException("Error creating excel export for query",e);
		}
		return new byte[0];
	}

	private static WritableCellFormat getOddFormat(Alignment alignment) throws WriteException {
		WritableCellFormat cs = getTextFormat(alignment);
		cs.setBackground(Colour.PALE_BLUE);
		return cs;
	}

	private static WritableCellFormat getEvenFormat(Alignment alignment) throws WriteException {
		WritableCellFormat cs = getTextFormat(alignment);
		cs.setBackground(Colour.BLUE);
		return cs;
	}

	private static WritableCellFormat getNumberFormat() throws WriteException {
		WritableCellFormat cs = new WritableCellFormat(new NumberFormat("###,###,###.0#")); //$NON-NLS-1$
		cs.setBorder(Border.ALL, BorderLineStyle.THIN);
		cs.setAlignment(Alignment.RIGHT);
		cs.setIndentation(1);
		return cs;
	}

	private static WritableCellFormat getTextFormat(Alignment alignment) throws WriteException {
		WritableCellFormat cs = new WritableCellFormat(new WritableFont(WritableFont.createFont("Verdana"),11)); //$NON-NLS-1$
		cs.setBorder(Border.ALL, BorderLineStyle.THIN);
		cs.setIndentation(1);
		cs.setAlignment(alignment);
		return cs;
	}

	public static boolean isDouble(String obj){
		try{
			Double.parseDouble(obj);
		}catch(NumberFormatException e){
			return false;
		}
		return true;
	}
}
