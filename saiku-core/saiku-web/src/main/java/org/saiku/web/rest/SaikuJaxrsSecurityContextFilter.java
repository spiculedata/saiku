/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.security.Principal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1732 — make JAX-RS {@code @RolesAllowed} deterministic across platforms and JVM launches.
 *
 * <p><b>The bug.</b> Jersey runs as a plain servlet ({@code ServletContainer} mapped {@code /rest/*}),
 * so its default {@link SecurityContext#isUserInRole(String)} delegates to
 * {@code HttpServletRequest.isUserInRole}. Under Spring Security that comes from the
 * {@code SecurityContextHolderAwareRequestWrapper}, but whether that wrapper reaches Jersey's
 * {@code SecurityContext} is non-deterministic (servlet/filter init-order + resource-registration
 * dependent) — observed flipping admin requests between 200 (wrapper present) and 403 (raw request,
 * which knows nothing of the Spring authorities) for the same admin credential across separate JVM
 * launches and across resource classes. That intermittently 403s the admin console in production.
 *
 * <p><b>The fix.</b> Source the role check from the request thread's {@link SecurityContextHolder}
 * — which IS reliably populated by the Spring filter chain before the request reaches Jersey (the
 * same source {@code SessionService} reads inside resource methods) — and install it as the JAX-RS
 * {@link SecurityContext} at {@link Priorities#AUTHENTICATION} (1000), so it is in place before
 * Jersey's {@code RolesAllowedDynamicFeature} authorization filter runs at
 * {@link Priorities#AUTHORIZATION} (2000) on the same thread. {@code isUserInRole} then matches the
 * Spring {@link GrantedAuthority} set directly and deterministically.
 *
 * <p><b>Boundary is untouched.</b> The load-bearing admin gate is the Spring URL rule
 * {@code intercept-url /rest/saiku/admin/** hasRole('ADMIN')} in {@code applicationContext-saiku.xml}
 * — evaluated in the filter chain long before Jersey. Non-admin -> 403 and anonymous -> 401 come
 * from there and are unchanged. This filter only makes the redundant JAX-RS layer AGREE with that
 * gate for a genuine admin: a {@code ROLE_USER}-only principal still fails
 * {@code isUserInRole("ROLE_ADMIN")} here (its authorities do not contain it), so nothing is opened.
 *
 * <p>Role matching accepts both the full {@code ROLE_}-prefixed authority and the bare form so a
 * {@code @RolesAllowed("ADMIN")} (bare) and {@code @RolesAllowed("ROLE_ADMIN")} (prefixed) both work
 * — today every annotation is {@code "ROLE_ADMIN"}, but this keeps a future bare name from silently
 * failing closed.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class SaikuJaxrsSecurityContextFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Thread-safety (no cross-request identity leak on a reused pool thread): the Spring
        // FilterChainProxy is mapped at /* (web.xml) and wraps this Jersey servlet, so
        // SecurityContextHolderFilter has already set THIS request's context into the (default
        // MODE_THREADLOCAL) holder earlier in the same request+thread, and clears it in a finally
        // block when the chain unwinds. So on a reused thread we read the current request's auth,
        // never a leftover: an unauthenticated request carries the AnonymousAuthenticationFilter's
        // AnonymousAuthenticationToken (rejected below), not a prior user. The role check runs
        // synchronously right after this filter on the same thread (no async dispatch), so the value
        // can't go stale between here and RolesAllowedDynamicFeature.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Leave the default context in place when there's no real authentication — null OR an
        // anonymous token both mean the Spring URL gate already decided access (401/403); we never
        // fabricate a principal or inject a role here.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        boolean secure = requestContext.getSecurityContext() != null
                && requestContext.getSecurityContext().isSecure();
        requestContext.setSecurityContext(new SpringAuthoritySecurityContext(authentication, secure));
    }

    /**
     * A JAX-RS {@link SecurityContext} backed by the Spring {@link Authentication}: {@code isUserInRole}
     * checks the granted-authority set directly (order-independent, no servlet-wrapper dependency).
     */
    private static final class SpringAuthoritySecurityContext implements SecurityContext {

        private final Authentication authentication;
        private final boolean secure;

        SpringAuthoritySecurityContext(Authentication authentication, boolean secure) {
            this.authentication = authentication;
            this.secure = secure;
        }

        @Override
        public Principal getUserPrincipal() {
            return authentication;
        }

        @Override
        public boolean isUserInRole(String role) {
            if (role == null) {
                return false;
            }
            String withPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            for (GrantedAuthority granted : authentication.getAuthorities()) {
                String a = granted.getAuthority();
                if (a == null) {
                    continue;
                }
                // Match the annotation value verbatim OR against the ROLE_-normalised form, so both
                // @RolesAllowed("ROLE_ADMIN") and a bare @RolesAllowed("ADMIN") resolve.
                if (a.equals(role) || a.equals(withPrefix)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public String getAuthenticationScheme() {
            return SecurityContext.BASIC_AUTH;
        }
    }
}
