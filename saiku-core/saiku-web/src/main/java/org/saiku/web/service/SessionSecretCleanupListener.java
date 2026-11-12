/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.service;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * saiku#2341: when an HTTP session ends (logout, idle timeout, container shutdown) drop the Saiku
 * session entry that belongs to it and zero its cached pass-through credential, so neither outlives
 * the login. Registered in {@code web.xml}; the bean is resolved lazily because the container may
 * destroy sessions while the Spring context is still starting or already closed.
 */
public class SessionSecretCleanupListener implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(SessionSecretCleanupListener.class);

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        try {
            WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(
                    event.getSession().getServletContext());
            if (ctx == null) {
                return;
            }
            SessionService sessionService = ctx.getBean(SessionService.class);
            sessionService.httpSessionDestroyed(event.getSession().getId());
        } catch (RuntimeException e) {
            log.debug("Could not clear the Saiku session for an ended HTTP session", e);
        }
    }
}
