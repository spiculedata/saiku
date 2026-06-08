/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.util.xml;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import org.jdom2.input.SAXBuilder;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * XML parser hardening helpers — used wherever Saiku deserialises XML from a source that could be
 * user-influenced (saved {@code .sds} datasource files, uploaded Mondrian schemas, SVG fed to the
 * PDF exporter). Centralises the OWASP-recommended set of features so we can't accidentally
 * regress: DOCTYPE is disallowed (defeats classic XXE), external general/parameter entities are
 * disabled (defence in depth), and {@link XMLConstants#FEATURE_SECURE_PROCESSING} is enabled.
 */
public final class SecureXml {

    private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private SecureXml() {}

    /** Build a {@link SAXParserFactory} that refuses DOCTYPE declarations and external entities. */
    public static SAXParserFactory secureSaxParserFactory() throws SAXException, ParserConfigurationException {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        spf.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
        spf.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
        spf.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
        spf.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);
        spf.setNamespaceAware(true);
        spf.setXIncludeAware(false);
        return spf;
    }

    /** Build a {@link DocumentBuilder} that refuses DOCTYPE declarations and external entities. */
    public static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
        dbf.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
        dbf.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
        dbf.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        // Namespace awareness is REQUIRED for downstream consumers that
        // read prefixed attributes / elements (XSLT compilation in
        // PdfReport, for one — without it Xalan reads `xsl:stylesheet`
        // as a non-qualified element and fails with "stylesheet requires
        // attribute: version", which produced an empty 15-byte PDF on
        // 2026-06-07). Matches the SAX factory above which DOES set
        // this; the omission here was an oversight.
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder();
    }

    /**
     * Build a JDOM2 {@link SAXBuilder} that refuses DOCTYPE declarations and external entities.
     * Use this instead of {@code new SAXBuilder()} anywhere user-influenced XML is parsed with
     * JDOM2 (e.g. saved-query deserialisation in {@code QueryDeserializer}). With
     * {@code disallow-doctype-decl} set, any inbound DOCTYPE is rejected outright, which defeats
     * both classic XXE (file read / SSRF) and entity-expansion DoS ("billion laughs"). Saved-query
     * XML never legitimately carries a DOCTYPE, so this is strictly hardening with no behaviour
     * change for valid input.
     */
    public static SAXBuilder secureSaxBuilder() {
        SAXBuilder builder = new SAXBuilder();
        builder.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
        builder.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
        builder.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
        builder.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);
        builder.setExpandEntities(false);
        return builder;
    }

    /**
     * JAXB unmarshalling routed through a hardened SAX parser. Use this instead of
     * {@code unmarshaller.unmarshal(stream)} so external-entity expansion (XXE) is impossible.
     */
    public static Object secureUnmarshal(JAXBContext ctx, InputStream stream)
            throws JAXBException, SAXException, ParserConfigurationException, IOException {
        SAXParser parser = secureSaxParserFactory().newSAXParser();
        XMLReader reader = parser.getXMLReader();
        SAXSource source = new SAXSource(reader, new InputSource(stream));
        Unmarshaller unmarshaller = ctx.createUnmarshaller();
        return unmarshaller.unmarshal(source);
    }
}
