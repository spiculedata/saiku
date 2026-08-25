/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link HttpSession} associated with the current request.
 *
 * <p>Historical behaviour (pre-multi-tenant): this bean cached the first
 * {@link HttpSession} it ever saw via the {@code HttpSessionCreatedEvent}
 * listener, then returned that same reference forever. That was fine
 * when Saiku was single-tenant — sessions were homogeneous so any one
 * would do. It is fatal under multi-tenant deployment: per-request
 * attributes (notably {@code workspace}) only ever existed on the
 * first-ever session, so every subsequent request read stale data and
 * the {@code workspaces=true} flag became cosmetic-only.
 *
 * <p>New behaviour: {@link #getSession()} consults Spring's
 * {@link RequestContextHolder} to find the current request's session.
 * The legacy cached field is retained as a fallback for code paths
 * that fire outside an HTTP request scope (background bootstrap,
 * scheduled tasks); the listener is still wired so those paths see a
 * sensible session reference rather than {@code null}.
 *
 * <p>The cached field is also a backstop for the
 * {@code FilesystemRepositoryManager.bootstrap()} call chain, which
 * runs from {@code init()} during Spring context refresh — at that
 * point there's no request scope at all and the fallback path keeps
 * the bootstrap working.
 */
public class ScopedRepo implements ApplicationListener<HttpSessionCreatedEvent>, Serializable {

    static final long serialVersionUID = 2L;

    private static final Logger log = LoggerFactory.getLogger(ScopedRepo.class);

    /**
     * Fallback reference used when no request scope is active. Set by
     * the session-created listener AND by {@link #setSession(HttpSession)}
     * for explicit operator wiring.
     */
    private transient HttpSession fallbackSession;

    public ScopedRepo() {}

    @Override
    public void onApplicationEvent(HttpSessionCreatedEvent sessionEvent) {
        // Always overwrite — the most recently observed session is the
        // safer fallback than the original first-ever one. This is a
        // no-op for normal request handling (getSession() reads
        // RequestContextHolder first) but matters for the bootstrap
        // path that runs outside any request scope.
        this.fallbackSession = sessionEvent.getSession();
    }

    public void setSession(HttpSession httpSession) {
        this.fallbackSession = httpSession;
    }

    /**
     * @return the current request's session if one is bound to the
     *         thread, otherwise the most recently observed session
     *         (which may be {@code null} during early bootstrap).
     */
    public HttpSession getSession() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession current = attrs.getRequest().getSession(false);
                if (current != null) {
                    return current;
                }
            }
        } catch (Exception e) {
            // RequestContextHolder can throw IllegalStateException when
            // called outside a request scope (bootstrap, scheduled
            // tasks). Expected — fall through to the cached reference.
            log.trace("no request scope on thread; using cached fallback session", e);
        }
        return this.fallbackSession;
    }
}
