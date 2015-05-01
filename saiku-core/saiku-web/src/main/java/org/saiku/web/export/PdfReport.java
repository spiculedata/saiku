package org.saiku.web.export;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.print.PrintTranscoder;
import org.apache.commons.lang.StringUtils;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.PdfPerformanceLogger;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;

/**
 * This PdfReport reads in a QueryResult and converts it to HTML, DOM, FO and eventually to a byte array containing the PDF data
 */
public class PdfReport {

    private static final Logger log = LoggerFactory.getLogger(PdfReport.class);

    private static final float marginLeft = 15;
    private static final float marginRight = 15;
    private static final float marginTop = 10;
    private static final float marginBottom = 10;

    PdfPerformanceLogger pdfPerformanceLogger;

    public PdfReport() {
        pdfPerformanceLogger = new PdfPerformanceLogger();
    }

    public byte[] createPdf(QueryResult queryResult, String svg) throws Exception {
        Document document = createDocumentWithSizeToContainQueryResult(queryResult);
        document.open();

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        populatePdf(queryResult, pdf);

        // do we want to add a svg image?
        if (StringUtils.isNotBlank(svg)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            addSvgImage(svg, document, writer);
        }

        //  document.close();
        return pdf.toByteArray();
    }

    private Document createDocumentWithSizeToContainQueryResult(QueryResult queryResult) {
        int resultWidth = calculateResultWidth(queryResult);
        Rectangle size = calculateDocumentSize(resultWidth);
        Document document = createDocumentWithMargins(size);
        return document;
    }

    private Document createDocumentWithMargins(Rectangle size) {
        return new Document(size, marginLeft, marginRight, marginTop, marginBottom);
    }

    private int calculateResultWidth(QueryResult queryResult) {
        int length = 0;
        if (queryResult != null && queryResult.getCellset() != null && queryResult.getCellset().size() > 0) {
            length = queryResult.getCellset().get(0).length;
        }
        if (length == 0) {
            throw new SaikuServiceException("Cannot convert empty result to PDF");
        }
        return length;

    }

    private void addSvgImage(String svg, Document document, PdfWriter pdfWriter) {
        document.newPage();
        StringBuffer stringBuffer = new StringBuffer(svg);
        if (!svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" ")) {
            stringBuffer.insert(stringBuffer.indexOf("<svg") + 4, " xmlns='http://www.w3.org/2000/svg'");
        }

        String t = "<?xml version='1.0' encoding='ISO-8859-1'"
            + " standalone='no'?>" + stringBuffer.toString();
        PdfContentByte cb = pdfWriter.getDirectContent();
        cb.saveState();
        cb.concatCTM(1.0f, 0, 0, 1.0f, 36, 0);
        float width = document.getPageSize().getWidth() - 20;
        float height = document.getPageSize().getHeight() - 20;
        Graphics2D graphics = cb.createGraphics(width, height);
        //graphics.rotate(Math.toRadians(-90), 100, 100);
        PrintTranscoder prm = new PrintTranscoder();
        TranscoderInput ti = new TranscoderInput(new StringReader(t));
        prm.transcode(ti, null);
        PageFormat pg = new PageFormat();
        Paper paper = new Paper();
        paper.setSize(width, height);
        paper.setImageableArea(5, 5, width, height);
        pg.setPaper(paper);
        prm.print(graphics, pg, 0);
        graphics.dispose();
        cb.restoreState();
    }

    private Rectangle calculateDocumentSize(int resultWidth) {
        Rectangle size = PageSize.A4.rotate();
        if (resultWidth > 8) {
            size = PageSize.A3.rotate();
        }
        if (resultWidth > 16) {
            size = PageSize.A2.rotate();
        }
        if (resultWidth > 32) {
            size = PageSize.A1.rotate();
        }
        if (resultWidth > 64) {
            size = PageSize.A0.rotate();
        }
        return size;
    }

    /**
     * Query to HTML, HTML to DOM, DOM to FO and FO is written as PDF Byte array
     *
     * @param queryResult
     * @param pdf
     * @throws Exception
     */
    public void populatePdf(QueryResult queryResult, OutputStream pdf) throws Exception {
        String htmlContent = generateContentAsHtmlString(queryResult);
        org.w3c.dom.Document htmlDom = DomConverter.getDom(htmlContent);
        org.w3c.dom.Document foDoc = FoConverter.getFo(htmlDom);
        byte[] formattedPdfContent = fo2Pdf(foDoc, null);
        tryWritingContentToPdfStream(pdf, formattedPdfContent);

        pdfPerformanceLogger.renderStop();
        pdfPerformanceLogger.logResults();
    }

    private void tryWritingContentToPdfStream(OutputStream pdf, byte[] formattedPdfContent) {
        try {
            pdf.write(formattedPdfContent);
        } catch (java.io.FileNotFoundException e) {
            e.printStackTrace();
            System.out.println("Error creating PDF: ");
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Error writing PDF: ");
        }
    }

    private String generateContentAsHtmlString(QueryResult queryResult) throws IOException {
        pdfPerformanceLogger.queryToHtmlStart();
        String contentBeforeQueryResult = createExportedByMessage();
        String queryResultContent = JSConverter.convertToHtml(queryResult);
        pdfPerformanceLogger.setQueryToHtmlStop();
        pdfPerformanceLogger.renderStart();
        return contentBeforeQueryResult + queryResultContent;
    }

    private String createExportedByMessage() {
        DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        Date date = new Date();
        return "<p>" + "Saiku Export - " + dateFormat.format(date) + "</p>";
    }

    private byte[] fo2Pdf(org.w3c.dom.Document foDocument, String styleSheet) {
        FopFactory fopFactory = FopFactory.newInstance();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, out);
            Transformer transformer = getTransformer(styleSheet);
            Source src = new DOMSource(foDocument);
            Result res = new SAXResult(fop.getDefaultHandler());

            transformer.transform(src, res);

            return out.toByteArray();

        } catch (Exception ex) {
            return null;
        }
    }

    private Transformer getTransformer(String styleSheet) {
        try {
            TransformerFactory tFactory = TransformerFactory.newInstance();

            DocumentBuilderFactory dFactory = DocumentBuilderFactory.newInstance();
            dFactory.setNamespaceAware(true);

            InputStream is = this.getClass().getResourceAsStream("xhtml2fo.xsl");
            DocumentBuilder dBuilder = dFactory.newDocumentBuilder();
            org.w3c.dom.Document xslDoc = dBuilder.parse(is);
            DOMSource xslDomSource = new DOMSource(xslDoc);

            return tFactory.newTransformer(xslDomSource);
        } catch (javax.xml.transform.TransformerException e) {
            e.printStackTrace();
            return null;
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return null;
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            e.printStackTrace();
            return null;
        } catch (org.xml.sax.SAXException e) {
            e.printStackTrace();
            return null;
        }
    }
}
