/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CorsSupportTest {

    @Test
    void echoesExactAllowedOrigin() {
        assertEquals(
                "https://app.saiku.cloud",
                CorsSupport.allowedOrigin(
                        "https://app.saiku.cloud", "https://app.saiku.cloud,https://x.test"));
    }

    @Test
    void nullWhenNotAllowed() {
        assertNull(CorsSupport.allowedOrigin("https://evil.test", "https://app.saiku.cloud"));
    }

    @Test
    void nullWhenNoOriginHeader() {
        assertNull(CorsSupport.allowedOrigin(null, "https://app.saiku.cloud"));
    }

    @Test
    void nullWhenNoAllowListConfigured() {
        assertNull(CorsSupport.allowedOrigin("https://app.saiku.cloud", null));
        assertNull(CorsSupport.allowedOrigin("https://app.saiku.cloud", ""));
    }

    @Test
    void wildcardAllowsAnyOrigin_echoingIt() {
        assertEquals("https://anything.test", CorsSupport.allowedOrigin("https://anything.test", "*"));
    }

    @Test
    void trimsAndIgnoresBlankEntries() {
        assertEquals("https://x.test", CorsSupport.allowedOrigin("https://x.test", " https://x.test , "));
    }
}
