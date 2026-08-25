/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.export;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import org.w3c.dom.Document;

class FoConverter {

    public static Document getFo(Document xmlDoc) {
        try {
            return xml2Fo(xmlDoc);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private static Document xml2Fo(Document xml) throws javax.xml.transform.TransformerException {
        DOMSource xmlDomSource = new DOMSource(xml);
        DOMResult domResult = new DOMResult();
        Transformer transformer = createTransformer();
        transformer.transform(xmlDomSource, domResult);
        return (org.w3c.dom.Document) domResult.getNode();
    }

    private static Transformer createTransformer() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();
        // Harden the XSLT factory against external entity / stylesheet resolution (XXE / SSRF).
        // Wrapped defensively: not every TransformerFactory implementation supports every
        // property, and an unsupported one must not break document conversion.
        try {
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception e) {
            // Feature unsupported by this factory implementation; continue.
        }
        try {
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (Exception e) {
            // Attribute unsupported by this factory implementation; continue.
        }
        try {
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (Exception e) {
            // Attribute unsupported by this factory implementation; continue.
        }
        Transformer transformer = factory.newTransformer();

        if (transformer == null) {
            System.out.println("Error creating transformer");
            throw new TransformerConfigurationException("Transformer is null");
        }
        return transformer;
    }
}
