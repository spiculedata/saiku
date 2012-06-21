package org.saiku.service.util.export.excel;

import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.service.util.exception.SaikuServiceException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: sramazzina
 * Date: 21/06/12
 * Time: 7.35
 * To change this template use File | Settings | File Templates.
 */

public class ExcelWorksheetBuilder {

    private static final String BASIC_SHEET_FONT_FAMILY = "Arial";
    private static final short BASIC_SHEET_FONT_SIZE = 11;

    private AbstractBaseCell[][] rowsetHeader;
    private AbstractBaseCell[][] rowsetBody;
    private HSSFWorkbook excelWorkbook;
    private Sheet workbookSheet;
    private int topLeftCornerWidth;
    private int topLeftCornerHeight;
    private HSSFCellStyle basicCS;
    private HSSFCellStyle numberCS;
    private HSSFCellStyle dateCS;
    private HSSFCellStyle lighterHeaderCellCS;
    private HSSFCellStyle darkerHeaderCellCS;

    public ExcelWorksheetBuilder(CellDataSet table, String sheetName) {

        init(table, sheetName);
    }

    protected void init(CellDataSet table, String sheetName) {

        excelWorkbook = new HSSFWorkbook();
        workbookSheet = excelWorkbook.createSheet(sheetName);

        rowsetHeader = table.getCellSetHeaders();
        rowsetBody = table.getCellSetBody();

        topLeftCornerWidth = findTopLeftCornerWidth();
        topLeftCornerHeight = findTopLeftCornerHeight();

        initCellStyles();
    }

    protected void initCellStyles() {

        Font font = excelWorkbook.createFont();
        font.setFontHeightInPoints((short) BASIC_SHEET_FONT_SIZE);
        font.setFontName(BASIC_SHEET_FONT_FAMILY);

        basicCS = excelWorkbook.createCellStyle();
        basicCS.setFont(font);
        basicCS.setAlignment(CellStyle.ALIGN_LEFT);
        setCellBordersColor(basicCS);

        numberCS = excelWorkbook.createCellStyle();
        numberCS.setFont(font);
        numberCS.setAlignment(CellStyle.ALIGN_RIGHT);
        setCellBordersColor(numberCS);

        Font headerFont = excelWorkbook.createFont();
        headerFont.setFontHeightInPoints((short) BASIC_SHEET_FONT_SIZE);
        headerFont.setFontName(BASIC_SHEET_FONT_FAMILY);
        headerFont.setBoldweight(Font.BOLDWEIGHT_BOLD);

        lighterHeaderCellCS = excelWorkbook.createCellStyle();
        lighterHeaderCellCS.setFont(headerFont);
        lighterHeaderCellCS.setAlignment(CellStyle.ALIGN_CENTER);
        lighterHeaderCellCS.setFillForegroundColor(HSSFColor.GREY_25_PERCENT.index);
        lighterHeaderCellCS.setFillPattern(HSSFCellStyle.SOLID_FOREGROUND);
        setCellBordersColor(lighterHeaderCellCS);

        darkerHeaderCellCS = excelWorkbook.createCellStyle();
        darkerHeaderCellCS.setFont(headerFont);
        darkerHeaderCellCS.setAlignment(CellStyle.ALIGN_CENTER);
        darkerHeaderCellCS.setFillForegroundColor(HSSFColor.GREY_40_PERCENT.index);
        darkerHeaderCellCS.setFillPattern(HSSFCellStyle.SOLID_FOREGROUND);
        setCellBordersColor(darkerHeaderCellCS);

    }

    protected void setCellBordersColor(HSSFCellStyle style) {

        style.setBorderBottom(CellStyle.BORDER_THIN);
        style.setBottomBorderColor(HSSFColor.GREY_80_PERCENT.index);
        style.setBorderTop(CellStyle.BORDER_THIN);
        style.setTopBorderColor(HSSFColor.GREY_80_PERCENT.index);
        style.setBorderLeft(CellStyle.BORDER_THIN);
        style.setLeftBorderColor(HSSFColor.GREY_80_PERCENT.index);
        style.setBorderRight(CellStyle.BORDER_THIN);
        style.setRightBorderColor(HSSFColor.GREY_80_PERCENT.index);
    }


    public byte[] build() throws SaikuServiceException {

        int lastHeaderRow = buildExcelTableHeader();
        addExcelTableRows(lastHeaderRow);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        try {
            excelWorkbook.write(bout);
        } catch (IOException e) {
            throw new SaikuServiceException("Error creating excel export for query",e);
        }
        return bout.toByteArray();
    }

    private void addExcelTableRows(int startingRow) {

        Row sheetRow = null;
        Cell cell = null;

        for (int x = 0; x< rowsetBody.length ;x++) {

            sheetRow = workbookSheet.createRow((short) x + startingRow);
            for(int y = 0; y < rowsetBody[x].length;y++) {
                cell = sheetRow.createCell(y);
                String value = rowsetBody[x][y].getFormattedValue();
                if (rowsetBody[x][y] instanceof DataCell && ((DataCell) rowsetBody[x][y]).getRawNumber() != null ) {
                    Number numberValue = ((DataCell) rowsetBody[x][y]).getRawNumber();
                    cell.setCellStyle(numberCS);
                    cell.setCellValue(numberValue.doubleValue());
                } else {
                    cell.setCellStyle(basicCS);
                    cell.setCellValue(value);
                }
            }
        }
    }

    protected int buildExcelTableHeader() {

        Row sheetRow = null;
        int x = 0;
        for (x = 0; x < rowsetHeader.length;x++) {
            sheetRow = workbookSheet.createRow((short) x);
            for(int y = 0; y < rowsetHeader[x].length; y++) {
                if (topLeftCornerHeight > 0 && x >= topLeftCornerHeight) {
                    fillHeaderCell(sheetRow, rowsetHeader[x][y].getFormattedValue(), y);
                } else if ((topLeftCornerHeight > 0 && x < topLeftCornerHeight) &&
                        (topLeftCornerWidth > 0 && y >= topLeftCornerWidth)) {
                    fillHeaderCell(sheetRow, rowsetHeader[x][y].getFormattedValue(), y);
                } else if (topLeftCornerHeight == 0 && topLeftCornerWidth == 0)
                    fillHeaderCell(sheetRow, rowsetHeader[x][y].getFormattedValue(), y);
            }
        }

        if (topLeftCornerHeight > 0 && topLeftCornerWidth > 0) {
            fillHeaderCellMerged(sheetRow);
        }

        return x;
    }

    private void fillHeaderCell(Row sheetRow, String formattedValue, int y) {
        Cell cell = sheetRow.createCell(y);
        cell.setCellValue(formattedValue);
        cell.setCellStyle(lighterHeaderCellCS);
    }

    private void fillHeaderCellMerged(Row sheetRow) {
        workbookSheet.addMergedRegion(new CellRangeAddress(0, topLeftCornerHeight-1, 0, topLeftCornerWidth-1));
    }


    /**
     * Find the width in cells of the top left corner of the table
     * @return
     */
    private int findTopLeftCornerWidth() {

        int width = 0;
        int x=0;
        boolean exit = (rowsetHeader[0][0].getRawValue() != null);
        String cellValue = null;

        for (x = 0; (!exit && rowsetHeader[0].length > x); x++) {

            cellValue = rowsetHeader[0][x].getRawValue();
            if ( cellValue == null) {
                width = x+1;
            } else {
                exit = true;
            }
        }

        return width;
    }

    /**
     * Find the height in cells of the top left corner of the table
     * @return
     */
    private int findTopLeftCornerHeight() {

        int height = rowsetHeader.length-1;
        return height;
    }

}
