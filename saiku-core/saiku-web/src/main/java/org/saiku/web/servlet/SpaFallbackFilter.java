/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

/**
 * SPA fallback for the SvelteKit UI at {@code /ui/*}.
 *
 * <p>The SvelteKit static adapter is configured with
 * {@code fallback: "index.html"} and routes like
 * {@code /dashboards/[...path]/} are marked {@code prerender = false} —
 * meaning the build does not emit a file at every possible deep URL.
 * Without server-side help, refreshing a page at e.g.
 * {@code /ui/dashboards/homes/admin/sales.saikudash} hits Jetty's
 * default servlet, which can't find the file and returns a 404.
 *
 * <p>This filter wraps the response, lets the default servlet attempt
 * its lookup, and if the result is a 404 on a path that looks like a
 * SPA route (no asset-style extension), it discards the 404 and
 * dispatches forward to {@code /ui/index.html} which contains the
 * SvelteKit fallback HTML. The client then takes the URL from
 * {@code window.location} and hydrates the right route.
 *
 * <p>Asset extensions (.js / .css / .svg / .png / .woff / .ico / .json /
 * .map / .html / .txt / .webmanifest) are passed straight through —
 * a missing static asset is genuinely a 404 and shouldn't be
 * masked by the fallback (or it'd produce a confusing infinite-load
 * for the script tags inside index.html).
 *
 * <p>Paths under {@code /ui/branding/*} are also passed straight
 * through; the {@link BrandingServlet} handles those with its own
 * 404 semantics (defaults are expected to be missing).
 */
public class SpaFallbackFilter implements Filter {

    private static final String FALLBACK = "/ui/index.html";
    private static final String BRANDING_PREFIX = "/ui/branding/";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }

        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        if (!shouldFallback(path)) {
            chain.doFilter(request, response);
            return;
        }

        StatusCapturingResponse wrapper = new StatusCapturingResponse(resp);
        chain.doFilter(request, wrapper);

        if (wrapper.getStatus() == HttpServletResponse.SC_NOT_FOUND && !resp.isCommitted()) {
            wrapper.discardCapturedError();
            resp.setStatus(HttpServletResponse.SC_OK);
            RequestDispatcher dispatcher = req.getRequestDispatcher(FALLBACK);
            dispatcher.forward(req, resp);
        }
    }

    /**
     * @return true if this request should attempt the SPA fallback on a
     *         404. False for everything outside /ui/, for the branding
     *         overlay, for the fallback target itself, and for asset
     *         requests whose extension implies a real static file.
     */
    static boolean shouldFallback(String path) {
        if (path == null || !path.startsWith("/ui/")) return false;
        if (path.equals(FALLBACK)) return false;
        if (path.startsWith(BRANDING_PREFIX)) return false;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot >= 0) {
            String ext = path.substring(dot + 1).toLowerCase();
            return !ASSET_EXTENSIONS.contains(ext);
        }
        // No extension — looks like an SPA route segment.
        return true;
    }

    private static final java.util.Set<String> ASSET_EXTENSIONS = java.util.Set.of(
            "js",
            "mjs",
            "css",
            "map",
            "html",
            "htm",
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
            "wasm");

    /**
     * Captures the status code so the filter can decide whether to
     * forward to the fallback. Also suppresses the body that the
     * default servlet writes for a 404 — without this, even a
     * successful forward would emit the 404 page first and the
     * fallback second.
     */
    private static final class StatusCapturingResponse extends HttpServletResponseWrapper {
        private int status = HttpServletResponse.SC_OK;

        StatusCapturingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            this.status = sc;
            if (sc == HttpServletResponse.SC_NOT_FOUND) {
                // Swallow — the filter will decide to forward on the
                // way back up. Returning here keeps Jetty's
                // ErrorHandler from writing its body.
                return;
            }
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            if (sc == HttpServletResponse.SC_NOT_FOUND) {
                return;
            }
            super.sendError(sc, msg);
        }

        @Override
        public int getStatus() {
            return status;
        }

        /** Called when we decide to use the fallback — clears any
         *  partial buffer from the suppressed 404 path. */
        void discardCapturedError() {
            if (!isCommitted()) {
                resetBuffer();
            }
        }
    }
}
