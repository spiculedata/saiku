/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.jdom2.JDOMException;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;

/**
 * Deep-audit regression: saved-query XML deserialisation must reject DOCTYPE
 * declarations so a crafted query body cannot mount an XXE (file read / SSRF)
 * or entity-expansion DoS. {@link QueryDeserializer} now builds its JDOM2
 * parser via {@code SecureXml.secureSaxBuilder()} (disallow-doctype-decl).
 *
 * <p>{@code getFakeCube(xml)} is the smallest reachable entry point (no OLAP
 * connection needed) and exercises the exact hardened parser path used by the
 * {@code POST /saiku/api/query/{name}} create flow.
 */
public class QueryDeserializerXxeTest {

    private static final String XXE_PAYLOAD = "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE Query [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
            + "<Query connection=\"c\" cube=\"&xxe;\" catalog=\"cat\" schema=\"sch\"/>";

    private static final String NORMAL_QUERY =
            "<Query connection=\"foodmart\" cube=\"Sales\" catalog=\"cat\" schema=\"sch\"/>";

    @Test
    public void doctypePayloadIsRejected() throws Exception {
        try {
            new QueryDeserializer().getFakeCube(XXE_PAYLOAD);
            fail("expected the DOCTYPE-bearing payload to be rejected by the hardened parser");
        } catch (JDOMException e) {
            // Parse-layer rejection (disallow-doctype-decl) — exactly what we want.
            // Pre-fix, the default SAXBuilder parsed this and expanded the entity.
            assertTrue(
                    "rejection should be about the disallowed DOCTYPE, was: " + e.getMessage(),
                    e.getMessage().toUpperCase().contains("DOCTYPE"));
        }
    }

    @Test
    public void normalQueryStillParses() throws Exception {
        SaikuCube cube = new QueryDeserializer().getFakeCube(NORMAL_QUERY);
        assertNotNull("a DOCTYPE-free query must still parse", cube);
        assertEquals("Sales", cube.getName());
        assertEquals("foodmart", cube.getConnection());
    }
}
