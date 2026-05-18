/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the path-classification logic in {@link SpaFallbackFilter}. */
public class SpaFallbackFilterTest {

    @Test
    public void spaRouteSegmentsFallback() {
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/dashboards"));
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/dashboards/homes/admin/sales.saikudash"));
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/admin"));
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/admin/datasources"));
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/workspace"));
    }

    @Test
    public void uiRootFallsBack() {
        // /ui/ itself doesn't have an extension — let it fall through to
        // the fallback (the default servlet would serve index.html anyway
        // via welcome-file, but if that's not configured this catches it).
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/"));
    }

    @Test
    public void assetExtensionsPassThrough() {
        for (String ext : new String[] {
            "js",
            "mjs",
            "css",
            "map",
            "html",
            "json",
            "txt",
            "xml",
            "svg",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "ico",
            "webp",
            "avif",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "eot",
            "webmanifest",
            "wasm"
        }) {
            String path = "/ui/_app/immutable/chunks/x." + ext;
            assertFalse("expected " + ext + " to pass through", SpaFallbackFilter.shouldFallback(path));
        }
    }

    @Test
    public void fallbackTargetDoesNotLoop() {
        // The forward target itself must NOT match — would loop.
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/index.html"));
    }

    @Test
    public void brandingOverlayPassesThrough() {
        // Branding 404s are intentional — the operator may not ship a
        // custom logo; the UI's <img onerror> falls back to defaults.
        // Wrapping these in the SPA fallback would mask the 404 and
        // serve index.html as if it were the missing logo.
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/branding/logo.svg"));
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/branding/logo.png"));
    }

    @Test
    public void nonUiPathsPassThrough() {
        assertFalse(SpaFallbackFilter.shouldFallback("/rest/saiku/info"));
        assertFalse(SpaFallbackFilter.shouldFallback("/xmla/foo"));
        assertFalse(SpaFallbackFilter.shouldFallback("/login"));
        assertFalse(SpaFallbackFilter.shouldFallback("/"));
    }

    @Test
    public void nullSafe() {
        assertFalse(SpaFallbackFilter.shouldFallback(null));
    }

    @Test
    public void mixedCaseExtensionsClassifiedCorrectly() {
        // Real-world: some browsers / clients lowercase the URL, some
        // don't. Filter must be case-insensitive on the extension
        // classification.
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/logo.SVG"));
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/icon.PNG"));
        assertFalse(SpaFallbackFilter.shouldFallback("/ui/x.JS"));
    }

    @Test
    public void dotInSegmentNameNotMistakenForExtension() {
        // The dashboard repository path can contain dots in directory
        // names (e.g. user "data.science" or a folder named "v1.0").
        // The filter should still recognise the trailing segment as a
        // SPA route, not as an extension.
        assertTrue(SpaFallbackFilter.shouldFallback("/ui/dashboards/v1.0/sales"));
    }
}
