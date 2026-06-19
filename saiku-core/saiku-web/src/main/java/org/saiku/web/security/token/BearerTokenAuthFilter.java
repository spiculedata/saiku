/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security filter that authenticates requests carrying an
 * {@code Authorization: Bearer <token>} header.
 *
 * <p>Delegates validation to the injected {@link TokenValidator}. On a valid
 * token it sets a request-scoped {@link UsernamePasswordAuthenticationToken} in
 * the Spring Security context and clears it in a {@code finally} block so the
 * credential is never written to the {@code HttpSession}.
 *
 * <p>Requests without a {@code Bearer} prefix, or with a token that the
 * validator rejects, pass through unchanged — downstream filters (HTTP Basic,
 * session cookie) then apply as normal.
 *
 * <p>Modelled on {@link org.saiku.web.security.share.ShareTokenAuthFilter} for
 * the OncePerRequestFilter + SecurityContext + finally-clear pattern.
 */
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final TokenValidator validator;

    public BearerTokenAuthFilter(TokenValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String authz = req.getHeader("Authorization");
        if (authz == null || !authz.startsWith("Bearer ")) {
            chain.doFilter(req, resp);
            return;
        }
        Optional<AuthedPrincipal> principal =
                validator.validate(authz.substring(7).trim());
        if (principal.isEmpty()) {
            chain.doFilter(req, resp);
            return;
        }
        AuthedPrincipal p = principal.get();
        var authorities = p.roles().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
        var auth = new UsernamePasswordAuthenticationToken(p.username(), null, authorities);
        try {
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
