/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Answers every {@code *.jsp} URL now that the WAR ships no JSPs and the launcher has no JSP
 * engine (saiku#1949). Without it Jetty's default JSP mapping returns a 500 ("JSP support not
 * configured") for any such path, including ones that never existed.
 *
 * <p>The legacy {@code /index.jsp} landing URL is redirected to the SPA at {@code /ui/}; every
 * other JSP path is a plain 404.
 */
public class LegacyJspServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String LANDING_PATH = "/index.jsp";
    private static final String SPA_PATH = "/ui/";

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (LANDING_PATH.equals(req.getServletPath())) {
            resp.sendRedirect(req.getContextPath() + SPA_PATH);
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
