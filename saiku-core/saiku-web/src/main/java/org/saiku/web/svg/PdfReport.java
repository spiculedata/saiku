package org.saiku.web.svg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.print.PrintTranscoder;
import org.apache.commons.lang.StringUtils;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;

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

import com.lowagie.text.pdf.BaseFont;
import org.saiku.olap.util.SaikuProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdfReport {
    private ReportData section= new ReportData(); 
    private static final Logger log = LoggerFactory.getLogger(PdfReport.class);
    private Font font = null; 
    private String fontName = BaseFont.HELVETICA;
	private Float fontSize = 8f;
	private Integer fontStyle = Font.NORMAL;
	private String fontEncoding = "UTF-8";
	private Color fontColor = Color.BLACK;
    
	public byte[] pdf(CellDataSet c, String svg) throws DocumentException, IOException {
		section.setRowBody(c.getCellSetBody());
		section.setRowHeader(c.getCellSetHeaders());
		
		Document document = new Document(PageSize.A4.rotate(),0,0,30,10);
		Color color = WebColors.getRGBColor("#002266");
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int dim = section.dimTab(c.getCellSetBody(), c.getCellSetHeaders());
		
		try {
			PdfWriter writer = PdfWriter.getInstance(document, baos);
			document.open();			
			DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
			Date date = new Date();
			document.setHeader(new HeaderFooter(new Phrase("Saiku Export - " + dateFormat.format(date) + " Page: "), null));
			
			ArrayList<ReportData.Section> rowGroups = section.section(c.getCellSetBody(), c.getCellSetHeaders(), 0, dim,
					null);

			populatePdf(document, rowGroups, dim,color,0);

			// do we want to add a svg image?
			if (StringUtils.isNotBlank(svg)) {
				document.newPage();
				StringBuffer s1 = new StringBuffer(svg);
				if (!svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" ")) {
					s1.insert(s1.indexOf("<svg") + 4, " xmlns='http://www.w3.org/2000/svg'");
				}

				String t = "<?xml version='1.0' encoding='ISO-8859-1'"
						+ " standalone='no'?>" + s1.toString();
				PdfContentByte cb = writer.getDirectContent();
				cb.saveState();
				cb.concatCTM(1.0f, 0, 0, 1.0f, 36, 0);
				float width = document.getPageSize().getWidth() - 20;
				float height = document.getPageSize().getHeight() - 20;
				Graphics2D g2 = cb.createGraphics(width, height);
				//g2.rotate(Math.toRadians(-90), 100, 100);
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
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return baos.toByteArray();
	}

	public Color color(Color c, float percent){
		Color end=new Color(255, 255, 255);
		 int r = c.getRed() + (int)(percent * (end.getRed() - c.getRed()));
		    int b = c.getBlue() + (int)(percent * (end.getBlue() - c.getBlue()));
		    int g = c.getGreen() + (int)(percent * (end.getGreen() - c.getGreen()));
		    c=new Color(r, g, b);
		    return c;
	}
	

	public void populatePdf(Document doc, ArrayList<ReportData.Section> section, int dim,Color color,float c) {
		for (int i = 0; i < section.size(); i++) {
			int temp = 1;
			if (section.get(i).getHead().size() != 0)
				temp = section.get(i).getHead().size();
			PdfPTable data = new PdfPTable(temp);
			data.setWidthPercentage(90);
			PdfPTable table = new PdfPTable(dim);
			table.setWidthPercentage(90);
			
			Font myFont = new Font(getFont());
			myFont.setColor(Color.WHITE);
			if(section.get(i).getDes()!=null){
				if(section.get(i).getParent()!=null && section.get(i).getParent().getDes()!=null ){
					section.get(i).setDes(section.get(i).getParent().getDes().trim()+"."+section.get(i).getDes().trim());
				}
			PdfPCell row = new PdfPCell(new Phrase(section.get(i).getDes(), myFont));
			row.setBackgroundColor(color);
			row.setBorder(Rectangle.NO_BORDER);
			row.setBorder(Rectangle.BOTTOM);
			row.setTop(100);
			row.setColspan(dim);
			table.addCell(row);
			table.setSpacingAfter(1);
			}
			
			if (section.get(i).getData() != null) {
				for (int x = 0; x < section.get(i).getHead().size(); x++) {
					PdfPCell cell = new PdfPCell(new Phrase(section.get(i)
							.getHead().get(x), new Font(getFont())));
					cell.setBackgroundColor(WebColors.getRGBColor("#B9D3EE"));
					cell.setBorder(Rectangle.NO_BORDER);
					cell.setBorder(Rectangle.BOTTOM);
					if(section.get(i)
							.getData()[0][section.get(i).getData()[0].length
											- section.get(i).getHead().size()+x].getClass().equals(DataCell.class))
					cell.setHorizontalAlignment( Element.ALIGN_RIGHT );
					else 
						cell.setHorizontalAlignment( Element.ALIGN_LEFT );
					data.addCell(cell);
					
				}
				for (int t = 0; t < section.get(i).getData().length; t++) {
					for (int x = section.get(i).getData()[0].length
							- section.get(i).getHead().size(); x < section.get(
							i).getData()[0].length; x++) {
						PdfPCell cell = new PdfPCell(new Phrase(section.get(i)
								.getData()[t][x].getFormattedValue(),
								new Font(getFont())));
						cell.setBorder(Rectangle.NO_BORDER);
						cell.setBorder(Rectangle.BOTTOM);
						int r=t %2;
						if(r!= 0)
						cell.setBackgroundColor(color(Color.BLACK,(float)0.92));
						
						if(section.get(i)
								.getData()[t][x].getClass().equals(DataCell.class))
						cell.setHorizontalAlignment( Element.ALIGN_RIGHT );
						else 
							cell.setHorizontalAlignment( Element.ALIGN_LEFT );
						data.addCell(cell);

					}
				}
			}

			try {
				doc.top(30);
				doc.add(table);
				doc.add(data);
			} catch (DocumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			populatePdf(doc, section.get(i).getChild(), dim,color(color,c+0.15f),c);
		}
	}

	private Font getFont() {
		if (font == null) {
			try {
				// load custom settings
				SaikuProperties properties = new SaikuProperties();
				if (properties.webExportPdfFontName != null)
					fontName = properties.webExportPdfFontName;

				if (properties.webExportPdfFontSize != null) {
					String strSize = properties.webExportPdfFontSize;
					try {
						fontSize = Float.valueOf(strSize);
					} catch (NumberFormatException e) {
						log.error("Invalid saiku.web.export.pdf.font.size value " + strSize + ", should be formatted like 8.5");
						e.printStackTrace();
					}
				}

				if (properties.webExportPdfFontEncoding != null)
					fontEncoding = properties.webExportPdfFontEncoding;

				if (properties.webExportPdfFontStyle != null) {
					String strStyle = properties.webExportPdfFontStyle;
					if (strStyle != null) {
						strStyle = strStyle.toUpperCase();
						fontStyle = Font.NORMAL;
						if (strStyle.contains("NORMAL")) fontStyle += Font.NORMAL;
						if (strStyle.contains("ITALIC")) fontStyle += Font.ITALIC;
						if (strStyle.contains("BOLD")) fontStyle += Font.BOLD;
						if (strStyle.contains("BOLDITALIC")) fontStyle += Font.BOLDITALIC;
						if (strStyle.contains("STRIKETHRU")) fontStyle += Font.STRIKETHRU;
						if (strStyle.contains("UNDERLINE")) fontStyle += Font.UNDERLINE;
					}
				}

				if (properties.webExportPdfFontColor != null) {
					String strColor = properties.webExportPdfFontColor;
					try {
						fontColor = 
							new Color(
					            Integer.valueOf( strColor.substring( 1, 3 ), 16 ),
					            Integer.valueOf( strColor.substring( 3, 5 ), 16 ),
					            Integer.valueOf( strColor.substring( 5, 7 ), 16 ));
					} catch (NumberFormatException e) {
						log.error("Invalid saiku.web.export.pdf.font.color value " + strColor + ", should be formatted like #abcdef");
						e.printStackTrace();
					}
				}

				font = new Font(BaseFont.createFont(fontName, fontEncoding, true));
			} catch (DocumentException e) {
				log.error("Could not create custom font, reverting to default");
				e.printStackTrace();
				font = FontFactory.getFont(FontFactory.HELVETICA, 8);
			} catch (IOException e) {
				log.error("Could not read font: " + fontName);
				e.printStackTrace();
			}
		}
		return font;
	}
}