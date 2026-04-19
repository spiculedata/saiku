package org.saiku.web.export;

import java.io.ByteArrayInputStream;
import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.DomSerializer;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;

class DomConverter {
    public static org.w3c.dom.Document getDom(String html) {
        ByteArrayInputStream input = new ByteArrayInputStream(html.getBytes());
        final HtmlCleaner cleaner = createHtmlCleanerWithProperties();
        DomSerializer doms = new DomSerializer(cleaner.getProperties(), false);
        try {
            TagNode node = cleaner.clean(input);
            return doms.createDOM(node);
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
