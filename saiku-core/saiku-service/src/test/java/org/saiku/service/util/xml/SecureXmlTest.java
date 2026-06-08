/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.util.xml;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * XXE-hardening contract for {@link SecureXml#secureUnmarshal(JAXBContext, java.io.InputStream)}.
 *
 * <p>The pre-hardening implementation in {@code FilesystemRepositoryManager.getDataSources()} called
 * {@code Unmarshaller.unmarshal(InputStream)} directly, which lets the default parser resolve external
 * entities. A malicious {@code .sds} datasource file with a {@code <!DOCTYPE ... <!ENTITY xxe SYSTEM
 * "file:///..."/>}  declaration could exfiltrate arbitrary files from the host. This test plants such
 * a payload and verifies the secure path rejects DOCTYPE rather than expanding the entity.
 */
public class SecureXmlTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rejects_external_entity_doctype() throws Exception {
        // Plant a "secret" file we wouldn't want exfiltrated.
        File secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), "TOP_SECRET".getBytes(StandardCharsets.UTF_8));

        String xxe = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \""
                + secret.toURI().toString()
                + "\">]>\n"
                + "<container><name>&xxe;</name></container>";

        JAXBContext ctx = JAXBContext.newInstance(Container.class);
        try {
            SecureXml.secureUnmarshal(ctx, new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)));
            fail("secure parser must REJECT the DOCTYPE-bearing payload, not parse it");
        } catch (Exception e) {
            // The original test treated ANY exception as "secure" and also passed if the parse
            // silently succeeded without leaking — so it could stay green even if the
            // disallow-doctype-decl control broke and some unrelated error fired. Pin the
            // mechanism instead: the secure path (disallow-doctype-decl=true) must fail BECAUSE
            // of the DOCTYPE, and the file contents must never surface anywhere in the failure.
            // Mirrors QueryDeserializerXxeTest's rigor on the JDOM2 parse path.
            String trace = throwableChainText(e);
            assertTrue(
                    "rejection must be the disallowed DOCTYPE, was: " + trace,
                    trace.toUpperCase(java.util.Locale.ROOT).contains("DOCTYPE"));
            assertFalse("file contents must never appear in the failure: " + trace, trace.contains("TOP_SECRET"));
        }
    }

    /** Full message text down the cause / linked-exception chain (JAXB wraps the SAX error). */
    private static String throwableChainText(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Test
    public void parses_benign_xml_normally() throws Exception {
        String xml = "<?xml version=\"1.0\"?><container><name>hello</name></container>";
        JAXBContext ctx = JAXBContext.newInstance(Container.class);
        Object result = SecureXml.secureUnmarshal(ctx, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertNotNull("benign XML must still unmarshal", result);
    }

    @XmlRootElement(name = "container")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Container {
        @XmlElement
        public String name;
    }
}
