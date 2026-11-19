/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link AccountStatusPolicy} to every authenticated request, just before authorisation
 * (saiku#2345): a user deactivated in the admin console or by SCIM gets a {@code 401} and their HTTP
 * session is ended on the very next request, and a user whose admin role was removed there continues
 * without it, so {@code hasRole('ADMIN')} rules and the admin resources refuse them.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    /** What to do to Saiku's own session state when an account is cut off or demoted. */
    public interface SessionHooks {
        /** The account is disabled: end its HTTP session and drop its cached Saiku session. */
        void onDisabled(HttpServletRequest req);

        /** The account was demoted: refresh the roles cached in its Saiku session. */
        void onDemoted(Authentication reduced);
    }

    private final AccountStatusPolicy policy;
    private final SessionHooks hooks;

    public AccountStatusFilter(AccountStatusPolicy policy, SessionHooks hooks) {
        this.policy = policy;
        this.hooks = hooks;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            chain.doFilter(req, resp);
            return;
        }
        Authentication effective = policy.evaluate(auth);
        if (effective == null) {
            hooks.onDisabled(req);
            SecurityContextHolder.clearContext();
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"ACCOUNT_DISABLED\",\"error\":\"This account is disabled.\"}");
            return;
        }
        if (effective != auth) {
            SecurityContextHolder.getContext().setAuthentication(effective);
            hooks.onDemoted(effective);
        }
        chain.doFilter(req, resp);
    }
}
