/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AddinManifestTest {
    @Test
    void deriveBaseUrl_prefersConfigOverride() {
        assertEquals(
                "https://saiku.acme.com",
                AddinManifest.deriveBaseUrl("https://saiku.acme.com/", null, null, "example.com", 443, "https"));
    }

    @Test
    void deriveBaseUrl_usesForwardedHeaders() {
        assertEquals(
                "https://saiku.acme.com",
                AddinManifest.deriveBaseUrl(null, "https", "saiku.acme.com", "internal-host", 8080, "http"));
    }

    @Test
    void deriveBaseUrl_forwardedHostMayIncludePort() {
        assertEquals(
                "https://saiku.acme.com:8443",
                AddinManifest.deriveBaseUrl(null, "https", "saiku.acme.com:8443", "x", 80, "http"));
    }

    @Test
    void deriveBaseUrl_fallsBackToRequest_nonDefaultPort() {
        assertEquals(
                "https://host.local:8443", AddinManifest.deriveBaseUrl(null, null, null, "host.local", 8443, "https"));
    }

    @Test
    void deriveBaseUrl_omitsDefaultHttpsPort() {
        assertEquals("https://host.local", AddinManifest.deriveBaseUrl(null, null, null, "host.local", 443, "https"));
    }

    @Test
    void buildManifest_embedsBaseUrl() {
        String xml = AddinManifest.buildManifest("https://saiku.acme.com");
        assertTrue(xml.contains("<SourceLocation DefaultValue=\"https://saiku.acme.com/addin/index.html\"/>"));
        assertTrue(xml.contains("<AppDomain>https://saiku.acme.com</AppDomain>"));
        assertTrue(xml.contains("https://saiku.acme.com/addin/assets/icon-32.png"));
        assertTrue(xml.contains("https://saiku.acme.com/addin/assets/icon-80.png"));
        assertTrue(xml.contains("<Version>1.0.0.0</Version>"));
        assertTrue(xml.contains("b6e9c1a2-3d4f-4a5b-8c7d-9e0f1a2b3c4d"));
    }
}
