package org.saiku.service.util.export.excel;

import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuSelection;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.service.util.exception.SaikuServiceException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

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
    private static final String EMPTY_STRING = "";
    private static final String CSS_COLORS_CODE_PROPERTIES = "/org/saiku/service/util/export/excel/resources/css-colors-codes.properties";

    private AbstractBaseCell[][] rowsetHeader;
    private AbstractBaseCell[][] rowsetBody;
    private HSSFWorkbook excelWorkbook;
    private Sheet workbookSheet;
    private String sheetName;
    private int topLeftCornerWidth;
    private int topLeftCornerHeight;
    private HSSFCellStyle basicCS;
    private HSSFCellStyle numberCS;
    private HSSFCellStyle lighterHeaderCellCS;
    private HSSFCellStyle darkerHeaderCellCS;
    private List<SaikuDimensionSelection> queryFilters;
    private Map<String, Integer> colorCodesMap;

    HSSFPalette customColorsPalette;
    int nextAvailableColorCode = 41;
    Properties cssColorCodesProperties;

    public ExcelWorksheetBuilder(CellDataSet table, List<SaikuDimensionSelection> filters, String sheetName) {

        init(table, filters, sheetName);
    }

    protected void init(CellDataSet table, List<SaikuDimensionSelection> filters, String sheetName) {

        queryFilters = filters;
        excelWorkbook = new HSSFWorkbook();
        customColorsPalette = excelWorkbook.getCustomPalette();
        colorCodesMap = new HashMap<String, Integer>();
        this.sheetName = sheetName;
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

        int startRow = initExcelSheet();
        int lastHeaderRow = buildExcelTableHeader(startRow);
        addExcelTableRows(lastHeaderRow);
        finalizeExcelSheet(startRow);

        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        try {
            excelWorkbook.write(bout);
        } catch (IOException e) {
            throw new SaikuServiceException("Error creating excel export for query", e);
        }
        return bout.toByteArray();
    }

    private void finalizeExcelSheet(int startRow) {

        int headerWidth = rowsetHeader.length;

        // Autosize columns
        for (int i=0; i<rowsetBody[0].length; i++) {
            workbookSheet.autoSizeColumn(i);
        }

        // Freeze the header columns
        workbookSheet.createFreezePane( 0, startRow + headerWidth, 0, startRow + headerWidth );
    }

    private int initExcelSheet() {

        initSummarySheet();
        // Main Workbook Sheet
        workbookSheet = excelWorkbook.createSheet(this.sheetName);

        return 0;
    }

    private void initSummarySheet() {

        // Main Workbook Sheet
        Sheet summarySheet = excelWorkbook.createSheet("Summary page");

        int row = 1;

        Row sheetRow = summarySheet.createRow((short) row);
        Cell cell = sheetRow.createCell(0);
        String todayDate = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date());
        cell.setCellValue("Export date and time: " + todayDate);
        summarySheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
        row = row+2;

        sheetRow = summarySheet.createRow((short) row);
        cell = sheetRow.createCell(0);
        cell.setCellValue("Dimension");
        cell = sheetRow.createCell(1);
        cell.setCellValue("Level");
        cell = sheetRow.createCell(2);
        cell.setCellValue("Filter Applied");
        row++;

        for (SaikuDimensionSelection item : queryFilters) {
            for (SaikuSelection s : item.getSelections()) {
                sheetRow = summarySheet.createRow((short) row);
                cell = sheetRow.createCell(0);
                cell.setCellValue(s.getDimensionUniqueName());
                cell = sheetRow.createCell(1);
                cell.setCellValue(s.getLevelUniqueName());
                cell = sheetRow.createCell(2);
                cell.setCellValue(s.getCaption());
                row++;
            }
        }

        row += 2;

        sheetRow = summarySheet.createRow((short) row);
        cell = sheetRow.createCell(0);
        cell.setCellValue("Export made using Saiku OLAP client.");
        summarySheet.addMergedRegion(new CellRangeAddress(row, row, 0, 10));

        // Autosize columns for summary sheet
        for (int i=0; i<5; i++) {
            summarySheet.autoSizeColumn(i);
        }
    }

    private void addExcelTableRows(int startingRow) {

        Row sheetRow = null;
        Cell cell = null;
        String formatString = null;

        for (int x = 0; x < rowsetBody.length; x++) {

            sheetRow = workbookSheet.createRow((short) x + startingRow);
            for (int y = 0; y < rowsetBody[x].length; y++) {
                cell = sheetRow.createCell(y);
                String value = rowsetBody[x][y].getFormattedValue();
                if (value == null) {
                    // If the row cells has a null values it means the value is repeated in the data internally
                    // but not in the interface. To properly format the Excel export file we need that value so we
                    // get it from the same position in the prev row
                    value = workbookSheet.getRow(sheetRow.getRowNum()-1).getCell(y).getStringCellValue();
                }
                if (rowsetBody[x][y] instanceof DataCell && ((DataCell) rowsetBody[x][y]).getRawNumber() != null) {
                    Number numberValue = ((DataCell) rowsetBody[x][y]).getRawNumber();
                    cell.setCellValue(numberValue.doubleValue());
                    applyCellFormatting(cell, x, y);
                } else {
                    cell.setCellStyle(basicCS);
                    cell.setCellValue(value);
                }
            }
        }
    }

    protected void applyCellFormatting(Cell cell, int x, int y) {
        String formatString;
        formatString = ((DataCell) rowsetBody[x][y]).getFormatString();
        if (formatString != null) {
            // Inherit formatting from cube schema FORMAT_STRING
            CellStyle numberCSClone = excelWorkbook.createCellStyle();
            DataFormat fmt = excelWorkbook.createDataFormat();
            numberCSClone.cloneStyleFrom(numberCS);
            numberCSClone.setDataFormat(fmt.getFormat(formatString));
            // Check for cell background
            Map<String, String> properties = ((DataCell) rowsetBody[x][y]).getProperties();
            if (properties.containsKey("style")) {
                short colorCodeIndex = getColorFromCustomPalette(properties.get("style"));
                if (colorCodeIndex != -1) {
                    numberCSClone.setFillForegroundColor(colorCodeIndex);
                    numberCSClone.setFillPattern(HSSFCellStyle.SOLID_FOREGROUND);
                }
            }
            cell.setCellStyle(numberCSClone);
        } else {
            cell.setCellStyle(numberCS);
        }

    }

    private short getColorFromCustomPalette(String style) {

        short returnedColorIndex = -1;
        InputStream is = null;

        if (colorCodesMap.containsKey(style)) {
            returnedColorIndex = colorCodesMap.get(style).shortValue();
        } else {
            try {

                if (cssColorCodesProperties == null) {
                is = getClass().getResourceAsStream(CSS_COLORS_CODE_PROPERTIES);
                    if (is != null) {
                        cssColorCodesProperties = new Properties();
                        cssColorCodesProperties.load(is);
                    }
                }

                String colorCode = cssColorCodesProperties.getProperty(style);
                if (colorCode != null) {
                    int redCode = Integer.parseInt(colorCode.substring(1, 3), 16);
                    int greenCode = Integer.parseInt(colorCode.substring(3, 5), 16);
                    int blueCode = Integer.parseInt(colorCode.substring(5, 7), 16);
                    customColorsPalette.setColorAtIndex(new Byte((byte) nextAvailableColorCode), new Byte((byte) redCode), new Byte((byte) greenCode), new Byte((byte) blueCode));
                    returnedColorIndex = customColorsPalette.getColor(nextAvailableColorCode).getIndex();
                    colorCodesMap.put(style, new Integer(returnedColorIndex));
                    nextAvailableColorCode++;
                }
            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } finally {
                try {
                    if (is != null)
                        is.close();
                } catch (IOException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }

        }

        return returnedColorIndex;  //To change body of created methods use File | Settings | File Templates.
    }

    protected int buildExcelTableHeader(int startRow) {

        Row sheetRow = null;
        int x = 0;
        int y = 0;
        int startSameFromPos = 0;
        int mergedCellsWidth = 0;
        boolean isLastHeaderRow = false;
        boolean isLastColumn = false;
        String nextHeader = EMPTY_STRING;
        String currentHeader = EMPTY_STRING;
        ArrayList<ExcelMergedRegionItemConfig> mergedItemsConfig = new ArrayList<ExcelMergedRegionItemConfig>();

        for (x = 0; x < rowsetHeader.length; x++) {

            sheetRow = workbookSheet.createRow((short) x + startRow);

            nextHeader = EMPTY_STRING;
            isLastColumn = false;
            startSameFromPos = 0;
            mergedCellsWidth = 0;

            if (x + 1 == rowsetHeader.length) isLastHeaderRow = true;

            for (y = 0; y < rowsetHeader[x].length; y++) {
                currentHeader = rowsetHeader[x][y].getFormattedValue();
                if (currentHeader != null) {
                    if (rowsetHeader[x].length == y+1)
                        isLastColumn = true;
                    else
                        nextHeader = rowsetHeader[x][y+1].getFormattedValue();

                    manageColumnHeaderDisplay(sheetRow, x, y, currentHeader);

                    if (!isLastHeaderRow) {
                        if (!nextHeader.equals(currentHeader) || isLastColumn) {
                            manageCellsMerge(y,
                                             x + startRow,
                                             mergedCellsWidth + 1,
                                             startSameFromPos,
                                             mergedItemsConfig);
                            startSameFromPos = y+1;
                            mergedCellsWidth = 0;
                        } else if (nextHeader.equals(currentHeader)) {
                            mergedCellsWidth++;
                        }
                    }
                } else
                    startSameFromPos++;
        }
        // Manage the merge condition on exit from columns scan
        if (!isLastHeaderRow)
            manageCellsMerge(y - 1, x, mergedCellsWidth+1, startSameFromPos, mergedItemsConfig);
        }

        if (topLeftCornerHeight > 0 && topLeftCornerWidth > 0) {
            workbookSheet.addMergedRegion(new CellRangeAddress(startRow, startRow + topLeftCornerHeight - 1, 0, topLeftCornerWidth - 1));
        }

        if (mergedItemsConfig.size()>0) {
            for (ExcelMergedRegionItemConfig item : mergedItemsConfig) {
                workbookSheet.addMergedRegion(new CellRangeAddress(item.getStartY(), item.getStartY() + item.getHeight(),
                                                                   item.getStartX(), item.getStartX() + item.getWidth() - 1));
            }
        }

        return x + startRow;
    }

    private void manageColumnHeaderDisplay(Row sheetRow, int x, int y, String currentHeader) {
        if (topLeftCornerHeight > 0 && x >= topLeftCornerHeight) {
            fillHeaderCell(sheetRow, currentHeader, y);
        } else if ((topLeftCornerHeight > 0 && x < topLeftCornerHeight) &&
                (topLeftCornerWidth > 0 && y >= topLeftCornerWidth)) {
            fillHeaderCell(sheetRow, currentHeader, y);
        } else if (topLeftCornerHeight == 0 && topLeftCornerWidth == 0)
            fillHeaderCell(sheetRow, currentHeader, y);
    }

    private void manageCellsMerge(int rowPos, int colPos,
                                  int width,
                                  int startSameFromPos,
                                  ArrayList<ExcelMergedRegionItemConfig> mergedItemsConfig) {


        ExcelMergedRegionItemConfig foundItem = null;
        boolean itemGetFromList = false;

        if (width == 1) return;

        for (ExcelMergedRegionItemConfig item : mergedItemsConfig) {
            if (item.getStartY() == colPos && item.getStartX() == rowPos) {
                foundItem = item;
                itemGetFromList = true;
            }
        }

        if (foundItem == null)
            foundItem = new ExcelMergedRegionItemConfig();

        foundItem.setHeight(0);
        foundItem.setWidth(width);
        foundItem.setStartX(startSameFromPos);
        foundItem.setStartY(colPos);
        if (mergedItemsConfig.isEmpty() || !itemGetFromList)
            mergedItemsConfig.add(foundItem);
    }

    private void fillHeaderCell(Row sheetRow, String formattedValue, int y) {
        Cell cell = sheetRow.createCell(y);
        cell.setCellValue(formattedValue);
        cell.setCellStyle(lighterHeaderCellCS);
    }


    /**
     * Find the width in cells of the top left corner of the table
     *
     * @return
     */
    private int findTopLeftCornerWidth() {

        int width = 0;
        int x = 0;
        boolean exit = (rowsetHeader[0][0].getRawValue() != null);
        String cellValue = null;

        for (x = 0; (!exit && rowsetHeader[0].length > x); x++) {

            cellValue = rowsetHeader[0][x].getRawValue();
            if (cellValue == null) {
                width = x + 1;
            } else {
                exit = true;
            }
        }

        return width;
    }

    /**
     * Find the height in cells of the top left corner of the table
     *
     * @return
     */
    private int findTopLeftCornerHeight() {

        int height = rowsetHeader.length - 1;
        return height;
    }

}
