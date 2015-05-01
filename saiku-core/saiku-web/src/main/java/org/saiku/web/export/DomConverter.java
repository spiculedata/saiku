package org.saiku.web.export;

import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.DomSerializer;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;

public class DomConverter {
    public static org.w3c.dom.Document getDom(String html) {
        ByteArrayInputStream input = new ByteArrayInputStream(html.getBytes());
        final HtmlCleaner cleaner = createHtmlCleanerWithProperties();
        DomSerializer doms = new DomSerializer(cleaner.getProperties(), false);
        try {
            TagNode node = cleaner.clean(input);
            org.w3c.dom.Document xmlDoc = doms.createDOM(node);
            return xmlDoc;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static HtmlCleaner createHtmlCleanerWithProperties() {
        HtmlCleaner cleaner = new HtmlCleaner();
        CleanerProperties props = cleaner.getProperties();
        props.setAdvancedXmlEscape(true);
        props.setRecognizeUnicodeChars(true);
        props.setTranslateSpecialEntities(true);
        return cleaner;
    }
}
