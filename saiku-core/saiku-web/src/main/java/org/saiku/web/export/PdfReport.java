package org.saiku.web.export;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.io.*;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.print.PrintTranscoder;
import org.apache.commons.lang3.StringUtils;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.service.util.export.PdfPerformanceLogger;
import org.saiku.web.rest.objects.resultset.QueryResult;
import org.xml.sax.SAXException;

/**
 * This PdfReport reads in a QueryResult and converts it to HTML, DOM, FO and eventually to a byte array containing the PDF data
 */
public class PdfReport {

    private static final float marginLeft = 15;
    private static final float marginRight = 15;
    private static final float marginTop = 10;
    private static final float marginBottom = 10;

    private final PdfPerformanceLogger pdfPerformanceLogger;

    public PdfReport() {
        pdfPerformanceLogger = new PdfPerformanceLogger();
    }

    public byte[] createPdf(QueryResult queryResult, String svg) throws Exception {
        Rectangle queryResultSize = getQueryResultSize(queryResult);

        Document document = createDocumentWithSizeToContainQueryResult(queryResultSize);
        document.open();

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        populatePdf(queryResult, pdf, queryResultSize);

        // do we want to add a svg image?
        if (StringUtils.isNotBlank(svg)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            addSvgImage(svg, document, writer);
        }

        //  document.close();
        return pdf.toByteArray();
    }

    private Document createDocumentWithSizeToContainQueryResult(Rectangle size) {
        return createDocumentWithMargins(size);
    }

    private Document createDocumentWithMargins(Rectangle size) {
        return new Document(size, marginLeft, marginRight, marginTop, marginBottom);
    }

    private int calculateResultWidth(QueryResult queryResult) {
        int length = 0;
        if (queryResult != null
                && queryResult.getCellset() != null
                && queryResult.getCellset().size() > 0) {
            length = queryResult.getCellset().get(0).length;
        }
        if (length == 0) {
            throw new SaikuServiceException("Cannot convert empty result to PDF");
        }

        return length;
    }

    private void addSvgImage(String svg, Document document, PdfWriter pdfWriter) {
        document.newPage();
        StringBuilder stringBuffer = new StringBuilder(svg);
        if (!svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" ")) {
            stringBuffer.insert(stringBuffer.indexOf("<svg") + 4, " xmlns='http://www.w3.org/2000/svg'");
        }

        String t = "<?xml version='1.0' encoding='ISO-8859-1'" + " standalone='no'?>" + stringBuffer.toString();
        PdfContentByte cb = pdfWriter.getDirectContent();
        cb.saveState();
        cb.concatCTM(1.0f, 0, 0, 1.0f, 36, 0);
        float width = document.getPageSize().getWidth() - 20;
        float height = document.getPageSize().getHeight() - 20;
        Graphics2D graphics = cb.createGraphics(width, height);
        // graphics.rotate(Math.toRadians(-90), 100, 100);
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
        Rectangle size = PageSize.A3.rotate();
        if (resultWidth > 8) {
            size = PageSize.A2.rotate();
        }
        if (resultWidth > 16) {
            size = PageSize.A1.rotate();
        }
        if (resultWidth > 24) {
            size = PageSize.A0.rotate();
        }
        if (resultWidth > 32) {
            size = PageSize.B0.rotate();
        }

        return size;
    }

    /**
     * Query to HTML, HTML to DOM, DOM to FO and FO is written as PDF Byte array
     *
     * @param queryResult
     * @param pdf
     * @param queryResultSize
     * @throws Exception
     */
    private void populatePdf(QueryResult queryResult, OutputStream pdf, Rectangle queryResultSize) throws Exception {
        String htmlContent = generateContentAsHtmlString(queryResult);
        org.w3c.dom.Document htmlDom = DomConverter.getDom(htmlContent);
        org.w3c.dom.Document foDoc = FoConverter.getFo(htmlDom);

        byte[] formattedPdfContent = fo2Pdf(foDoc, null, queryResultSize);
        tryWritingContentToPdfStream(pdf, formattedPdfContent);

        pdfPerformanceLogger.renderStop();
        pdfPerformanceLogger.logResults();
    }

    private void tryWritingContentToPdfStream(OutputStream pdf, byte[] formattedPdfContent) {
        try {
            pdf.write(formattedPdfContent);
        } catch (FileNotFoundException e) {
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

    private byte[] fo2Pdf(org.w3c.dom.Document foDocument, String styleSheet, Rectangle size) {
        FopFactoryBuilder builder = null;
        try {
            builder = new FopFactoryBuilder(
                    this.getClass().getResource("fop_config.xml").toURI());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        builder.setStrictFOValidation(false);

        try {
            // Specify an external configuration file, to ease user changes, like adding custom fonts
            FopFactory fopFactory = builder.build();

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, out);
            Transformer transformer = getTransformer(styleSheet);
            Source src = new DOMSource(foDocument);
            Result res = new SAXResult(fop.getDefaultHandler());

            if (transformer != null) {
                transformer.setParameter("page_height", (size.getHeight() / 72) + "in");
                transformer.setParameter("page_width", (size.getWidth() / 72) + "in");
                transformer.transform(src, res);
            }

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
        } catch (javax.xml.transform.TransformerException
                | ParserConfigurationException
                | SAXException
                | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Rectangle getQueryResultSize(QueryResult queryResult) {
        int resultWidth = calculateResultWidth(queryResult);
        return calculateDocumentSize(resultWidth);
    }
}
