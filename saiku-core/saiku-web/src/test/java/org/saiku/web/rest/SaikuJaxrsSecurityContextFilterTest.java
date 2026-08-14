/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1732 — unit cover for {@link SaikuJaxrsSecurityContextFilter}. The filter must set a JAX-RS
 * {@link SecurityContext} whose {@code isUserInRole} is sourced from the Spring authorities
 * (deterministic), so {@code @RolesAllowed("ROLE_ADMIN")} stops flipping 200/403. The IT
 * ({@code AdminIT}) proves it end to end; these pin the role-matching contract in isolation.
 *
 * <p>No mocking framework (saiku-web has none) — a tiny hand-rolled request context captures the
 * SecurityContext the filter installs.
 */
public class SaikuJaxrsSecurityContextFilterTest {

    private final SaikuJaxrsSecurityContextFilter filter = new SaikuJaxrsSecurityContextFilter();

    @After
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Run the filter and return whatever SecurityContext it set (null if it left the context alone). */
    private SecurityContext runFilterCapturingContext() {
        CapturingRequestContext req = new CapturingRequestContext();
        filter.filter(req);
        return req.installed;
    }

    private static void authenticateWith(String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("u", "p", granted));
    }

    @Test
    public void adminAuthorityMatchesRoleAdmin() {
        authenticateWith("ROLE_USER", "ROLE_ADMIN");
        SecurityContext ctx = runFilterCapturingContext();
        assertTrue("ROLE_ADMIN authority must satisfy isUserInRole(ROLE_ADMIN)", ctx.isUserInRole("ROLE_ADMIN"));
    }

    @Test
    public void nonAdminDoesNotMatchRoleAdmin() {
        // The load-bearing safety assertion: a ROLE_USER-only principal is NEVER granted ROLE_ADMIN
        // by this filter — no boundary loosening.
        authenticateWith("ROLE_USER");
        SecurityContext ctx = runFilterCapturingContext();
        assertFalse("ROLE_USER must NOT satisfy isUserInRole(ROLE_ADMIN)", ctx.isUserInRole("ROLE_ADMIN"));
    }

    @Test
    public void bareRoleNameAlsoMatches() {
        // Defensive: a future @RolesAllowed("ADMIN") (bare) must still resolve against ROLE_ADMIN.
        authenticateWith("ROLE_ADMIN");
        SecurityContext ctx = runFilterCapturingContext();
        assertTrue("bare 'ADMIN' must match the ROLE_ADMIN authority", ctx.isUserInRole("ADMIN"));
        assertTrue("prefixed 'ROLE_ADMIN' must also match", ctx.isUserInRole("ROLE_ADMIN"));
    }

    @Test
    public void bareNameMatchingDoesNotOverGrant() {
        // Over-match guard (SEC code-review ask): the bare-"ADMIN" branch must NOT grant a
        // non-admin. Saiku's auth config only ever produces ROLE_-prefixed authorities
        // (users.properties roles column = ROLE_USER / ROLE_ADMIN; hasRole('ADMIN') => ROLE_ADMIN),
        // so a bare "ADMIN" authority cannot arise — but pin it anyway: a ROLE_USER-only principal
        // is denied for BOTH the bare and prefixed forms.
        authenticateWith("ROLE_USER");
        SecurityContext ctx = runFilterCapturingContext();
        assertFalse("ROLE_USER must NOT match bare 'ADMIN'", ctx.isUserInRole("ADMIN"));
        assertFalse("ROLE_USER must NOT match 'ROLE_ADMIN'", ctx.isUserInRole("ROLE_ADMIN"));
    }

    @Test
    public void nullRoleIsNotGranted() {
        authenticateWith("ROLE_ADMIN");
        SecurityContext ctx = runFilterCapturingContext();
        assertFalse(ctx.isUserInRole(null));
    }

    @Test
    public void userPrincipalIsTheSpringAuthentication() {
        authenticateWith("ROLE_ADMIN");
        SecurityContext ctx = runFilterCapturingContext();
        assertSame(
                "getUserPrincipal must return the Spring Authentication",
                SecurityContextHolder.getContext().getAuthentication(),
                ctx.getUserPrincipal());
        assertEquals(SecurityContext.BASIC_AUTH, ctx.getAuthenticationScheme());
    }

    @Test
    public void anonymousAuthenticationLeavesContextUntouched() {
        // Anonymous => the URL gate already decided; the filter must NOT set a context.
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        SecurityContext ctx = runFilterCapturingContext();
        assertNull("anonymous must not trigger setSecurityContext", ctx);
    }

    @Test
    public void noAuthenticationLeavesContextUntouched() {
        SecurityContextHolder.clearContext();
        SecurityContext ctx = runFilterCapturingContext();
        assertNull("no authentication must not trigger setSecurityContext", ctx);
    }

    /**
     * Minimal {@link jakarta.ws.rs.container.ContainerRequestContext} that only implements what the
     * filter touches: {@link #getSecurityContext()} and {@link #setSecurityContext}. Everything else
     * throws so an accidental new dependency in the filter surfaces loudly.
     */
    private static final class CapturingRequestContext implements jakarta.ws.rs.container.ContainerRequestContext {
        private SecurityContext installed;

        @Override
        public SecurityContext getSecurityContext() {
            // Report the currently-installed context (initially an insecure default) so the filter's
            // isSecure() carry-over is exercised.
            return installed;
        }

        @Override
        public void setSecurityContext(SecurityContext context) {
            this.installed = context;
        }

        // --- unused surface: fail loudly if the filter ever reaches for it ---
        @Override
        public Object getProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Collection<String> getPropertyNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setProperty(String name, Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.UriInfo getUriInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRequestUri(java.net.URI requestUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRequestUri(java.net.URI baseUri, java.net.URI requestUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.Request getRequest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setMethod(String method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, String> getHeaders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getHeaderString(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Date getDate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Locale getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLength() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MediaType getMediaType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<java.util.Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasEntity() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getEntityStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntityStream(java.io.InputStream input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortWith(jakarta.ws.rs.core.Response response) {
            throw new UnsupportedOperationException();
        }
    }
}
