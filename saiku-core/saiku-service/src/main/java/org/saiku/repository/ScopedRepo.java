/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link HttpSession} of the request being served on the current thread.
 *
 * <p>It answers from the request and only from the request. There is deliberately no cached
 * "most recent session" to fall back to: such a field is process-wide, so a request that had
 * no session of its own (a stateless {@code /xmla} or SCIM call, a first request, background
 * work) was handed whichever user last form-logged-in, and callers read and even removed
 * attributes on that other user's session (#2344, CWE-488). Off-request code (the repository
 * bootstrap that runs during context refresh, scheduled tasks) gets {@code null}, which the
 * callers already tolerate.
 */
public class ScopedRepo {

    public ScopedRepo() {}

    /**
     * @return the current request's existing {@link HttpSession}, or {@code null} when no request
     *     is bound to this thread or the bound request has no session. Never another request's.
     */
    public HttpSession getSession() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getSession(false);
        }
        return null;
    }
}
