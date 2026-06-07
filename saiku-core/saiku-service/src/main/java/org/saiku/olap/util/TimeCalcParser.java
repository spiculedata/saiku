/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.olap.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.saiku.olap.dto.SaikuTimeCalc;
import org.saiku.service.util.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parse {@code <TimeCalcs>/<TimeCalc>} directives out of a Mondrian schema XML
 * for a single cube. Surfaces the result as {@link SaikuTimeCalc} instances so
 * the SPA's date-filter modal can render them as one-click measures (saiku#1221
 * Phase 3; declarations defined in mondrian-saiku#112).
 *
 * <p>The schema XML location is parsed out of the cube's datasource location
 * property — a Mondrian connection string of the form
 * {@code jdbc:mondrian:Jdbc=jdbc:h2:...;Catalog=file:/.../Bank.xml;...}. The
 * parser then walks
 * {@code /Schema/Cube[@name=<cubeName>]/TimeCalcs/TimeCalc} to extract the
 * directives.
 *
 * <p>Failures (missing file, malformed XML, no matching cube) return an empty
 * list rather than throwing — TimeCalc surfacing is a nice-to-have, never
 * load-bearing.
 *
 * <p>Uses {@link SecureXml#secureDocumentBuilder()} so DOCTYPE / external
 * entities are disabled (XXE-safe).
 */
public final class TimeCalcParser {

    private static final Logger log = LoggerFactory.getLogger(TimeCalcParser.class);

    private TimeCalcParser() {}

    /**
     * Parse TimeCalcs declared on the cube named {@code cubeName} inside the
     * Mondrian schema XML at {@code schemaXmlUri}. Returns an empty,
     * unmodifiable list when the schema is unreadable, the cube isn't found,
     * or the cube has no {@code <TimeCalcs>} section.
     *
     * @param schemaXmlUri a {@code file:} URI or absolute filesystem path
     * @param cubeName the cube to extract TimeCalcs for; case-sensitive match
     *     on the cube's {@code @name} attribute
     */
    public static List<SaikuTimeCalc> parse(String schemaXmlUri, String cubeName) {
        if (schemaXmlUri == null || schemaXmlUri.isBlank() || cubeName == null || cubeName.isBlank()) {
            return Collections.emptyList();
        }
        Path file = resolveLocal(schemaXmlUri);
        if (file == null || !Files.isReadable(file)) {
            return Collections.emptyList();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Document doc = SecureXml.secureDocumentBuilder().parse(in);
            return parseDocument(doc, cubeName);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            log.debug("TimeCalc parse failed for {} cube {} — surface nothing", file, cubeName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Extract TimeCalcs from a Mondrian datasource location string. Convenience
     * over {@link #parse(String, String)} for callers that hold the raw
     * Mondrian URL.
     */
    public static List<SaikuTimeCalc> parseFromDatasourceLocation(String datasourceLocation, String cubeName) {
        String catalog = extractCatalogUrl(datasourceLocation);
        if (catalog == null) return Collections.emptyList();
        return parse(catalog, cubeName);
    }

    /**
     * Pull the {@code Catalog=} value out of a Mondrian connection URL like
     * {@code jdbc:mondrian:Jdbc=jdbc:h2:...;Catalog=file:/path/to/Bank.xml;JdbcDrivers=...}.
     */
    public static String extractCatalogUrl(String datasourceLocation) {
        if (datasourceLocation == null) return null;
        for (String part : datasourceLocation.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "Catalog=", 0, "Catalog=".length())) {
                return trimmed.substring("Catalog=".length());
            }
        }
        return null;
    }

    /** Convert a {@code file:} URI or absolute path string to a {@link Path}. */
    private static Path resolveLocal(String schemaXmlUri) {
        try {
            if (schemaXmlUri.startsWith("file:")) {
                return Paths.get(URI.create(schemaXmlUri));
            }
            return Paths.get(schemaXmlUri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Walk the document to {@code Schema/Cube[@name=cubeName]/TimeCalcs/TimeCalc}
     *  and build {@link SaikuTimeCalc} per directive. Visible for tests. */
    static List<SaikuTimeCalc> parseDocument(Document doc, String cubeName) {
        List<SaikuTimeCalc> out = new ArrayList<>();
        Element root = doc.getDocumentElement();
        if (root == null) return out;
        NodeList cubes = root.getElementsByTagName("Cube");
        for (int i = 0; i < cubes.getLength(); i++) {
            Node n = cubes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element cubeEl = (Element) n;
            if (!cubeName.equals(cubeEl.getAttribute("name"))) continue;
            NodeList groups = cubeEl.getElementsByTagName("TimeCalcs");
            for (int g = 0; g < groups.getLength(); g++) {
                Node gn = groups.item(g);
                if (gn.getNodeType() != Node.ELEMENT_NODE) continue;
                NodeList directives = ((Element) gn).getElementsByTagName("TimeCalc");
                for (int d = 0; d < directives.getLength(); d++) {
                    Node dn = directives.item(d);
                    if (dn.getNodeType() != Node.ELEMENT_NODE) continue;
                    out.add(toDto((Element) dn));
                }
            }
        }
        return out;
    }

    private static SaikuTimeCalc toDto(Element el) {
        return new SaikuTimeCalc(
                attr(el, "name"),
                attr(el, "type"),
                attr(el, "measure"),
                attr(el, "timeDimension"),
                attrInt(el, "window"),
                attr(el, "function"),
                attr(el, "formatString"));
    }

    private static String attr(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Integer attrInt(Element el, String name) {
        String v = attr(el, name);
        if (v == null) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
