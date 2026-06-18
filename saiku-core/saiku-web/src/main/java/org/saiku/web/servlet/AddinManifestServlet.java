/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Serves the Office add-in manifest at /addin/manifest.xml with URLs for this host. */
public class AddinManifestServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String configUrl = System.getProperty("saiku.addin.publicUrl");
        if (configUrl == null) configUrl = getServletContext().getInitParameter("saiku.addin.publicUrl");
        String base = AddinManifest.deriveBaseUrl(
                configUrl,
                req.getHeader("X-Forwarded-Proto"),
                req.getHeader("X-Forwarded-Host"),
                req.getServerName(),
                req.getServerPort(),
                req.getScheme());
        resp.setContentType("application/xml");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(AddinManifest.buildManifest(base));
    }
}
