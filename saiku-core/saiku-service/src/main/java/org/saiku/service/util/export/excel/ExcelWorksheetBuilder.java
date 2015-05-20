package org.saiku.service.util.export.excel;

import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.util.SaikuProperties;
import org.saiku.service.util.exception.SaikuServiceException;

import org.apache.commons.lang.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static final String CSS_COLORS_CODE_PROPERTIES = "css-colors-codes.properties";

  private int maxRows = -1;
  private int maxColumns = -1;


  private AbstractBaseCell[][] rowsetHeader;
  private AbstractBaseCell[][] rowsetBody;
  private Workbook excelWorkbook;
  private Sheet workbookSheet;
  private String sheetName;
  private int topLeftCornerWidth;
  private int topLeftCornerHeight;
  private CellStyle basicCS;
  private CellStyle numberCS;
  private CellStyle lighterHeaderCellCS;
  private CellStyle darkerHeaderCellCS;
  private List<ThinHierarchy> queryFilters;
  private Map<String, Integer> colorCodesMap;

  private int nextAvailableColorCode = 41;
  private Properties cssColorCodesProperties;

  private HSSFPalette customColorsPalette;
  private ExcelBuilderOptions options;

  private Map<String, CellStyle> cellStyles = new HashMap<String, CellStyle>();

  private static final Logger log = LoggerFactory.getLogger(ExcelWorksheetBuilder.class);

  public ExcelWorksheetBuilder(CellDataSet table, List<ThinHierarchy> filters, ExcelBuilderOptions options) {
    init(table, filters, options);
  }

  protected void init(CellDataSet table, List<ThinHierarchy> filters, ExcelBuilderOptions options) {

    this.options = options;
    queryFilters = filters;
    maxRows = SpreadsheetVersion.EXCEL2007.getMaxRows();
    maxColumns = SpreadsheetVersion.EXCEL2007.getMaxColumns();

    if ("xls".equals(SaikuProperties.webExportExcelFormat)) {
      HSSFWorkbook wb = new HSSFWorkbook();
      customColorsPalette = wb.getCustomPalette();
      excelWorkbook = wb;
      maxRows = SpreadsheetVersion.EXCEL97.getMaxRows();
      maxColumns = SpreadsheetVersion.EXCEL97.getMaxColumns();
    } else if ("xlsx".equals(SaikuProperties.webExportExcelFormat)) {
      excelWorkbook = new XSSFWorkbook();
    } else {
      excelWorkbook = new XSSFWorkbook();
    }



    CreationHelper createHelper = excelWorkbook.getCreationHelper();

    colorCodesMap = new HashMap<String, Integer>();
    this.sheetName = options.sheetName;
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
        /* 
        justasg:
            Let's set default format, used if measure has no format at all.
            More info: http://poi.apache.org/apidocs/org/apache/poi/ss/usermodel/BuiltinFormats.html#getBuiltinFormat(int)
            If we don't have default format, it will output values up to maximum detail, i.e. 121212.3456789
            and we like them as 121,212.346
        */
    DataFormat fmt = excelWorkbook.createDataFormat();
    short dataFormat = fmt.getFormat(SaikuProperties.webExportExcelDefaultNumberFormat);
    numberCS.setDataFormat(dataFormat);


    Font headerFont = excelWorkbook.createFont();
    headerFont.setFontHeightInPoints((short) BASIC_SHEET_FONT_SIZE);
    headerFont.setFontName(BASIC_SHEET_FONT_FAMILY);
    headerFont.setBoldweight(Font.BOLDWEIGHT_BOLD);

    lighterHeaderCellCS = excelWorkbook.createCellStyle();
    lighterHeaderCellCS.setFont(headerFont);
    lighterHeaderCellCS.setAlignment(CellStyle.ALIGN_CENTER);
    lighterHeaderCellCS.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
    lighterHeaderCellCS.setFillPattern(CellStyle.SOLID_FOREGROUND);
    setCellBordersColor(lighterHeaderCellCS);

    darkerHeaderCellCS = excelWorkbook.createCellStyle();
    darkerHeaderCellCS.setFont(headerFont);
    darkerHeaderCellCS.setAlignment(CellStyle.ALIGN_CENTER);
    darkerHeaderCellCS.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
    darkerHeaderCellCS.setFillPattern(CellStyle.SOLID_FOREGROUND);
    setCellBordersColor(darkerHeaderCellCS);

  }

  protected void setCellBordersColor(CellStyle style) {

    style.setBorderBottom(CellStyle.BORDER_THIN);
    style.setBottomBorderColor(IndexedColors.GREY_80_PERCENT.getIndex());
    style.setBorderTop(CellStyle.BORDER_THIN);
    style.setTopBorderColor(IndexedColors.GREY_80_PERCENT.getIndex());
    style.setBorderLeft(CellStyle.BORDER_THIN);
    style.setLeftBorderColor(IndexedColors.GREY_80_PERCENT.getIndex());
    style.setBorderRight(CellStyle.BORDER_THIN);
    style.setRightBorderColor(IndexedColors.GREY_80_PERCENT.getIndex());
  }


  public byte[] build() throws SaikuServiceException {

    Long start = (new Date()).getTime();
    int startRow = initExcelSheet();
    Long init = (new Date()).getTime();
    int lastHeaderRow = buildExcelTableHeader(startRow);
    Long header = (new Date()).getTime();
    addExcelTableRows(lastHeaderRow);
    Long content = (new Date()).getTime();
    finalizeExcelSheet(startRow);
    Long finalizing = (new Date()).getTime();

    log.debug("Init: " + (init - start) + "ms header: " + (header - init) + "ms content: " + (content - header) + "ms finalizing: " + (finalizing - content) + "ms ");
    ByteArrayOutputStream bout = new ByteArrayOutputStream();

    try {
      excelWorkbook.write(bout);
    } catch (IOException e) {
      throw new SaikuServiceException("Error creating excel export for query", e);
    }
    return bout.toByteArray();
  }

  private void finalizeExcelSheet(int startRow) {



    boolean autoSize = (rowsetBody != null && rowsetBody.length > 0 && rowsetBody.length < 10000 && rowsetHeader != null && rowsetHeader.length > 0 && rowsetHeader[0].length < 200);

    if (autoSize) {
      log.warn("Skipping auto-sizing columns, more than 10000 rows and/or 200 columns");
    }

    Long start = (new Date()).getTime();
    if (autoSize) {
      // Autosize columns
      for (int i=0; i < maxColumns && i < rowsetBody[0].length; i++) {
        workbookSheet.autoSizeColumn(i);
      }
    }
    Long end = (new Date()).getTime();
    log.debug("Autosizing: " + (end - start) + "ms");
    // Freeze the header columns
    int headerWidth = rowsetHeader.length;
    workbookSheet.createFreezePane( 0, startRow + headerWidth, 0, startRow + headerWidth );
  }

  private int initExcelSheet() {
    // Main Workbook Sheet
    if (StringUtils.isNotBlank(options.sheetName)) {
      workbookSheet = excelWorkbook.createSheet(this.sheetName);
    } else {
      workbookSheet = excelWorkbook.createSheet();
    }
    initSummarySheet();
    return 0;
  }

  private void initSummarySheet() {

    // Main Workbook Sheet
    Sheet summarySheet = excelWorkbook.createSheet("Summary page");

    int row = 1;

    Row sheetRow = summarySheet.createRow((int) row);
    Cell cell = sheetRow.createCell(0);
    String todayDate = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date());
    cell.setCellValue("Export date and time: " + todayDate);
    summarySheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
    row = row+2;

    sheetRow = summarySheet.createRow((int) row);
    cell = sheetRow.createCell(0);
    cell.setCellValue("Dimension");
    cell = sheetRow.createCell(1);
    cell.setCellValue("Level");
    cell = sheetRow.createCell(2);
    cell.setCellValue("Filter Applied");
    row++;

    if (queryFilters != null) {
      for (ThinHierarchy item : queryFilters) {
        for (ThinLevel s : item.getLevels().values()) {
          for (ThinMember i : s.getSelection().getMembers()) {
            sheetRow = summarySheet.createRow((short) row);
            cell = sheetRow.createCell(0);
            cell.setCellValue(item.getCaption());
            cell = sheetRow.createCell(1);
            cell.setCellValue(s.getCaption());
            cell = sheetRow.createCell(2);
            cell.setCellValue(i.getCaption());
            row++;
          }
        }
      }
    }

    row += 2;

    int rowLength = (rowsetBody != null) ? rowsetBody.length : 0;
    int columnCount = (rowsetHeader != null && rowsetHeader.length > 0) ? rowsetHeader[0].length : 0;
    int headerLength = (rowsetHeader != null) ? rowsetHeader.length : 0;

    if (columnCount > maxColumns) {
      sheetRow = summarySheet.createRow((int) row);
      cell = sheetRow.createCell(0);
      cell.setCellValue("Excel sheet is truncated, only contains " + maxColumns + " columns of " + (columnCount));
      summarySheet.addMergedRegion(new CellRangeAddress(row, row, 0, 10));
      row++;
    }

    if ((headerLength + rowLength) > maxRows) {
      sheetRow = summarySheet.createRow((int) row);
      cell = sheetRow.createCell(0);
      cell.setCellValue("Excel sheet is truncated, only contains " + maxRows + " rows of " + ( headerLength + rowLength));
      summarySheet.addMergedRegion(new CellRangeAddress(row, row, 0, 10));
      row++;
    }

    row++;


    sheetRow = summarySheet.createRow((int) row);
    cell = sheetRow.createCell(0);
    cell.setCellValue(SaikuProperties.webExportExcelPoweredBy);
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

    if ((startingRow + rowsetBody.length) > maxRows) {
      log.warn("Excel sheet is truncated, only outputting " + maxRows + " rows of " + (rowsetBody.length + startingRow));
    }
    if (rowsetBody.length > 0 && rowsetBody[0].length > maxColumns) {
      log.warn("Excel sheet is truncated, only outputting " + maxColumns + " columns of " + (rowsetBody[0].length));
    }

    for (int x = 0; (x + startingRow)  < maxRows && x < rowsetBody.length; x++) {

      sheetRow = workbookSheet.createRow((int) x + startingRow);
      for (int y = 0; y < maxColumns && y < rowsetBody[x].length; y++) {
        cell = sheetRow.createCell(y);
        String value = rowsetBody[x][y].getFormattedValue();
        if (value == null && options.repeatValues) {
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
    if ((formatString != null) && (formatString.trim().length() > 0)) {

      String formatKey = "" + formatString;
      if (!cellStyles.containsKey(formatKey)) {
        // Inherit formatting from cube schema FORMAT_STRING
        CellStyle numberCSClone = excelWorkbook.createCellStyle();
        numberCSClone.cloneStyleFrom(numberCS);
        DataFormat fmt = excelWorkbook.createDataFormat();

        // the format string can contain macro values such as "Standard" from mondrian.util.Format
        // try and look it up, otherwise use the given one
        formatString = FormatUtil.getFormatString(formatString);
        try {
          short dataFormat = fmt.getFormat(formatString);
          numberCSClone.setDataFormat(dataFormat);
        } catch (Exception e) {
          // we tried to apply the mondrian format, but probably failed, so lets use the standard one
          //short dataFormat = fmt.getFormat(SaikuProperties.webExportExcelDefaultNumberFormat);
          //numberCSClone.setDataFormat(dataFormat);
        }
        cellStyles.put(formatKey, numberCSClone);
      }

      CellStyle numberCSClone = cellStyles.get(formatKey);

      // Check for cell background
      Map<String, String> properties = ((DataCell) rowsetBody[x][y]).getProperties();
      if (properties.containsKey("style")) {
        String colorCode = properties.get("style");
        short colorCodeIndex = getColorFromCustomPalette(colorCode);
        if (colorCodeIndex != -1) {
          numberCSClone.setFillForegroundColor(colorCodeIndex);
          numberCSClone.setFillPattern(CellStyle.SOLID_FOREGROUND);
        } else if (customColorsPalette == null) {
          try {

            if (cssColorCodesProperties != null && cssColorCodesProperties.containsKey(colorCode)) {
              colorCode = cssColorCodesProperties.getProperty(colorCode);
            }


            int redCode = Integer.parseInt(colorCode.substring(1, 3), 16);
            int greenCode = Integer.parseInt(colorCode.substring(3, 5), 16);
            int blueCode = Integer.parseInt(colorCode.substring(5, 7), 16);
            numberCSClone.setFillPattern(CellStyle.SOLID_FOREGROUND);
            ((XSSFCellStyle) numberCSClone).setFillForegroundColor(new XSSFColor(new java.awt.Color(redCode, greenCode, blueCode)));
            ((XSSFCellStyle) numberCSClone).setFillBackgroundColor(new XSSFColor(new java.awt.Color(redCode, greenCode, blueCode)));
          } catch (Exception e) {
            // we tried to set the color, no luck, lets continue without
          }

        }
      } else {

        numberCSClone.setFillForegroundColor(numberCS.getFillForegroundColor());
        numberCSClone.setFillBackgroundColor(numberCS.getFillBackgroundColor());
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
          try {
            int redCode = Integer.parseInt(colorCode.substring(1, 3), 16);
            int greenCode = Integer.parseInt(colorCode.substring(3, 5), 16);
            int blueCode = Integer.parseInt(colorCode.substring(5, 7), 16);
            if (customColorsPalette != null) {
              customColorsPalette.setColorAtIndex(new Byte((byte) nextAvailableColorCode), new Byte((byte) redCode), new Byte((byte) greenCode), new Byte((byte) blueCode));
              returnedColorIndex = customColorsPalette.getColor(nextAvailableColorCode).getIndex();
              colorCodesMap.put(style, new Integer(returnedColorIndex));
            } else {
              return -1;
            }
            nextAvailableColorCode++;
          } catch (Exception e) {
            // we tried to set the color, no luck, lets continue without
            return -1;
          }
        }
      } catch (IOException e) {
          log.error("IO Exception", e);
      } finally {
        try {
          if (is != null)
            is.close();
        } catch (IOException e) {
          log.error("IO Exception", e);
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

      sheetRow = workbookSheet.createRow((int) x + startRow);

      nextHeader = EMPTY_STRING;
      isLastColumn = false;
      startSameFromPos = 0;
      mergedCellsWidth = 0;

      if (x + 1 == rowsetHeader.length) isLastHeaderRow = true;

      for (y = 0; y < maxColumns && y < rowsetHeader[x].length; y++) {
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
        int lastCol = item.getStartX() + item.getWidth() - 1;
        lastCol = lastCol >= maxColumns ? maxColumns - 1 : lastCol;
        workbookSheet.addMergedRegion(new CellRangeAddress(item.getStartY(), item.getStartY() + item.getHeight(),
          item.getStartX(), lastCol));
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
    boolean exit = (rowsetHeader.length < 1 || rowsetHeader[0][0].getRawValue() != null);
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

    int height = rowsetHeader.length > 0 ? rowsetHeader.length - 1 : 0;
    return height;
  }

}
