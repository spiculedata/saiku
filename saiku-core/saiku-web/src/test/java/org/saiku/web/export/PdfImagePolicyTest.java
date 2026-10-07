/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.export;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringWriter;
import java.util.List;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.junit.Test;
import org.saiku.web.rest.objects.resultset.Cell;
import org.saiku.web.rest.objects.resultset.QueryResult;

public class PdfImagePolicyTest {

    @Test
    public void queryResultMarkupIsEscapedBeforeHtmlParsing() throws Exception {
        QueryResult result = new QueryResult(
                List.of(
                        new Cell[] {
                            new Cell("Year", Cell.Type.COLUMN_HEADER), new Cell("Sales", Cell.Type.COLUMN_HEADER)
                        },
                        new Cell[] {
                            new Cell("1997", Cell.Type.ROW_HEADER),
                            new Cell("<img src=\"http://127.0.0.1/private\">", Cell.Type.DATA_CELL)
                        }),
                0,
                2,
                1);

        String html = JSConverter.convertToHtml(result);
        assertTrue(html.contains("&lt;img"));
        assertFalse(html.contains("<img"));
    }

    @Test
    public void pdfStylesheetNeverEmitsExternalGraphicsFromQueryHtml() throws Exception {
        String html = "<html><head><title>Export</title></head><body><table><tr><td>"
                + "<img src='http://127.0.0.1/private'/>"
                + "<object type='image/png' data='file:///private/data'/>"
                + "</td></tr></table></body></html>";
        Transformer transformer = TransformerFactory.newInstance()
                .newTransformer(new StreamSource(PdfImagePolicyTest.class.getResourceAsStream("xhtml2fo.xsl")));
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(DomConverter.getDom(html)), new StreamResult(output));

        assertFalse(output.toString().contains("external-graphic"));
        assertFalse(output.toString().contains("127.0.0.1"));
        assertFalse(output.toString().contains("file:///"));
    }
}
