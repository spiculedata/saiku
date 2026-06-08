package org.saiku.web.svg;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.HeaderFooter;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.html.WebColors;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.print.PrintTranscoder;
import org.apache.commons.lang3.StringUtils;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdfReport {
    private final ReportData section = new ReportData();
    private static final Logger log = LoggerFactory.getLogger(PdfReport.class);

    public byte[] pdf(CellDataSet c, String svg) {
        section.setRowBody(c.getCellSetBody());
        section.setRowHeader(c.getCellSetHeaders());

        Document document = new Document(PageSize.A4.rotate(), 0, 0, 30, 10);
        Color color = WebColors.getRGBColor("#002266");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int dim = section.dimTab(c.getCellSetBody(), c.getCellSetHeaders());

        try {
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            document.open();
            DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
            Date date = new Date();
            document.setHeader(
                    new HeaderFooter(new Phrase("Saiku Export - " + dateFormat.format(date) + " Page: "), null));

            ArrayList<ReportData.Section> rowGroups =
                    section.section(c.getCellSetBody(), c.getCellSetHeaders(), 0, dim, null);

            populatePdf(document, rowGroups, dim, color, 0);

            // do we want to add a svg image?
            if (StringUtils.isNotBlank(svg)) {
                // saiku#1165: reject external refs / DOCTYPE before Batik parses it
                // (SSRF / local-file-read / XXE via user-supplied SVG).
                SvgSecurity.requireSafe(svg);
                document.newPage();
                StringBuilder s1 = new StringBuilder(svg);
                if (!svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" ")) {
                    s1.insert(s1.indexOf("<svg") + 4, " xmlns='http://www.w3.org/2000/svg'");
                }

                String t = "<?xml version='1.0' encoding='ISO-8859-1'" + " standalone='no'?>" + s1.toString();
                PdfContentByte cb = writer.getDirectContent();
                cb.saveState();
                cb.concatCTM(1.0f, 0, 0, 1.0f, 36, 0);
                float width = document.getPageSize().getWidth() - 20;
                float height = document.getPageSize().getHeight() - 20;
                Graphics2D g2 = cb.createGraphics(width, height);
                // g2.rotate(Math.toRadians(-90), 100, 100);
                PrintTranscoder prm = new PrintTranscoder();
                TranscoderInput ti = new TranscoderInput(new StringReader(t));
                prm.transcode(ti, null);
                PageFormat pg = new PageFormat();
                Paper pp = new Paper();
                pp.setSize(width, height);
                pp.setImageableArea(5, 5, width, height);
                pg.setPaper(pp);
                prm.print(g2, pg, 0);
                g2.dispose();
                cb.restoreState();
            }

            document.close();
        } catch (DocumentException e) {
            log.error("Error creating PDF", e);
        }
        return baos.toByteArray();
    }

    private Color color(Color c, float percent) {
        Color end = new Color(255, 255, 255);
        int r = c.getRed() + (int) (percent * (end.getRed() - c.getRed()));
        int b = c.getBlue() + (int) (percent * (end.getBlue() - c.getBlue()));
        int g = c.getGreen() + (int) (percent * (end.getGreen() - c.getGreen()));
        c = new Color(r, g, b);
        return c;
    }

    private void populatePdf(Document doc, ArrayList<ReportData.Section> section, int dim, Color color, float c) {
        for (ReportData.Section aSection : section) {
            int temp = 1;
            if (aSection.getHead().size() != 0) {
                temp = aSection.getHead().size();
            }
            PdfPTable data = new PdfPTable(temp);
            data.setWidthPercentage(90);
            PdfPTable table = new PdfPTable(dim);
            table.setWidthPercentage(90);

            Font myFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.WHITE);
            if (aSection.getDes() != null) {
                if (aSection.getParent() != null && aSection.getParent().getDes() != null) {
                    aSection.setDes(aSection.getParent().getDes().trim() + "."
                            + aSection.getDes().trim());
                }
                PdfPCell row = new PdfPCell(new Phrase(aSection.getDes(), myFont));
                row.setBackgroundColor(color);
                row.setBorder(Rectangle.NO_BORDER);
                row.setBorder(Rectangle.BOTTOM);
                row.setTop(100);
                row.setColspan(dim);
                table.addCell(row);
                table.setSpacingAfter(1);
            }

            if (aSection.getData() != null) {
                for (int x = 0; x < aSection.getHead().size(); x++) {
                    PdfPCell cell = new PdfPCell(
                            new Phrase(aSection.getHead().get(x), FontFactory.getFont(FontFactory.HELVETICA, 8)));
                    cell.setBackgroundColor(WebColors.getRGBColor("#B9D3EE"));
                    cell.setBorder(Rectangle.NO_BORDER);
                    cell.setBorder(Rectangle.BOTTOM);
                    if (aSection.getData()[0][
                            aSection.getData()[0].length - aSection.getHead().size() + x]
                            .getClass()
                            .equals(DataCell.class)) {
                        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    } else {
                        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                    }
                    data.addCell(cell);
                }
                for (int t = 0; t < aSection.getData().length; t++) {
                    for (int x = aSection.getData()[0].length
                                    - aSection.getHead().size();
                            x < aSection.getData()[0].length;
                            x++) {
                        PdfPCell cell = new PdfPCell(new Phrase(
                                aSection.getData()[t][x].getFormattedValue(),
                                FontFactory.getFont(FontFactory.HELVETICA, 8)));
                        cell.setBorder(Rectangle.NO_BORDER);
                        cell.setBorder(Rectangle.BOTTOM);
                        int r = t % 2;
                        if (r != 0) {
                            cell.setBackgroundColor(color(Color.BLACK, (float) 0.92));
                        }

                        if (aSection.getData()[t][x].getClass().equals(DataCell.class)) {
                            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        } else {
                            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                        }
                        data.addCell(cell);
                    }
                }
            }

            try {
                doc.top(30);
                doc.add(table);
                doc.add(data);
            } catch (DocumentException e) {
                log.error("Error creating PDF", e);
            }

            populatePdf(doc, aSection.getChild(), dim, color(color, c + 0.15f), c);
        }
    }
}
