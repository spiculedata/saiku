/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2618 — unit cover for {@link FeatureGateFilter}: the path→feature map,
 * the admin superuser rule, the deny path (403 FEATURE_DISABLED), and the
 * pass-through posture for anonymous callers (Spring's gate owns their 401/403).
 *
 * <p>No mocking framework (saiku-web has none) — a tiny hand-rolled request
 * context carries a fixed path and captures any abort.
 */
public class FeatureGateFilterTest {

    @After
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("u", "p", granted));
    }

    /** Run the filter for a path; return the aborted response or null when the request passed. */
    private Response run(String path) {
        StubRequestContext req = new StubRequestContext(path);
        new FeatureGateFilter(new org.saiku.service.features.FeatureToggles(
                        "/nonexistent/feature-toggles.json"))
                .filter(req);
        return req.aborted;
    }

    @Test
    public void everyCataloguePrefixMapsToItsFeature() {
        assertEquals("dashboards", FeatureGateFilter.featureOf("/saiku/api/dashboards"));
        assertEquals("dashboards", FeatureGateFilter.featureOf("/saiku/api/dashboards/comments"));
        assertEquals("apps", FeatureGateFilter.featureOf("/saiku/api/apps"));
        assertEquals("apps", FeatureGateFilter.featureOf("/saiku/api/apps/some/app.saikuapp"));
        assertEquals("notebooks", FeatureGateFilter.featureOf("/saiku/api/notebooks"));
        assertEquals("sql-workbench", FeatureGateFilter.featureOf("/saiku/sql-workbench/execute"));
        assertNull("an unrelated path is not gated", FeatureGateFilter.featureOf("/saiku/api/query"));
        assertNull("the admin surface is never feature-gated", FeatureGateFilter.featureOf("/saiku/admin/features"));
        assertNull("the share surface is never feature-gated", FeatureGateFilter.featureOf("/saiku/share/view/x"));
    }

    @Test
    public void prefixMustNotMatchMidSegment() {
        assertNull("a longer path sharing a prefix string is not gated",
                FeatureGateFilter.featureOf("/saiku/api/notebooks-extra/x"));
    }

    @Test
    public void missingConfigFileMeansEverythingPasses() {
        authenticateWith("ROLE_USER");
        assertNull("no config -> unrestricted -> pass", run("/saiku/api/notebooks"));
        assertNull(run("/saiku/api/dashboards"));
    }

    @Test
    public void adminAlwaysPassesEvenADeniedFeature() {
        org.saiku.service.features.FeatureToggles toggles =
                new org.saiku.service.features.FeatureToggles(denyingFile());
        FeatureGateFilter filter = new FeatureGateFilter(toggles);
        authenticateWith("ROLE_USER", "ROLE_ADMIN");
        StubRequestContext req = new StubRequestContext("/saiku/api/notebooks");
        filter.filter(req);
        assertNull("ROLE_ADMIN must never be feature-denied (the admin panel must stay reachable)", req.aborted);
    }

    @Test
    public void deniedNonAdminIsRefused403FeatureDisabled() {
        FeatureGateFilter filter =
                new FeatureGateFilter(new org.saiku.service.features.FeatureToggles(denyingFile()));
        authenticateWith("ROLE_USER");
        StubRequestContext req = new StubRequestContext("/saiku/api/notebooks/run");
        filter.filter(req);
        assertEquals(403, req.aborted.getStatus());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) req.aborted.getEntity();
        assertEquals(FeatureGateFilter.ERROR_CODE, body.get("error"));
        assertEquals("notebooks", body.get("feature"));
    }

    @Test
    public void grantedRolePasses() {
        org.saiku.service.features.FeatureToggles toggles =
                new org.saiku.service.features.FeatureToggles(denyingFile());
        // Grant notebooks back to the caller's own role.
        java.util.Map<String, List<String>> grants = new java.util.LinkedHashMap<>();
        grants.put("notebooks", List.of("ROLE_USER"));
        try {
            toggles.save(grants);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        authenticateWith("ROLE_USER");
        StubRequestContext req = new StubRequestContext("/saiku/api/notebooks");
        new FeatureGateFilter(toggles).filter(req);
        assertNull("a granted role passes", req.aborted);
    }

    @Test
    public void anonymousCallerIsNotDeniedByThisFilter() {
        // The Spring URL gate owns the 401/403 for anonymous callers; the feature
        // gate must not fabricate a principal or deny on their behalf.
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertNull(run("/saiku/api/notebooks"));
    }

    @Test
    public void noAuthenticationAtAllIsNotDeniedByThisFilter() {
        SecurityContextHolder.clearContext();
        assertNull(run("/saiku/api/notebooks"));
    }

    /** A config file that denies notebooks to everyone but admins. */
    private String denyingFile() {
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("feature-toggles", ".json");
            java.nio.file.Files.writeString(
                    file, "{\"features\":{\"notebooks\":{\"roles\":[]}}}");
            file.toFile().deleteOnExit();
            return file.toString();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Minimal {@link ContainerRequestContext} carrying a fixed path; everything
     * else throws so an accidental new dependency in the filter surfaces loudly.
     */
    private static final class StubRequestContext implements ContainerRequestContext {
        private final String path;
        private Response aborted;

        StubRequestContext(String path) {
            this.path = path;
        }

        @Override
        public UriInfo getUriInfo() {
            return new UriInfo() {
                @Override
                public String getPath() {
                    return path;
                }

                @Override
                public String getPath(boolean decode) {
                    return path;
                }

                @Override
                public List<jakarta.ws.rs.core.PathSegment> getPathSegments() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI getRequestUri() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI getAbsolutePath() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI getBaseUri() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters(boolean decode) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters(boolean decode) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<String> getMatchedURIs() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<String> getMatchedURIs(boolean decode) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<Object> getMatchedResources() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI resolve(java.net.URI uri) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI resolve(String uri) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.net.URI relativize(java.net.URI uri) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public void abortWith(Response response) {
            this.aborted = response;
        }

        @Override
        public void setSecurityContext(jakarta.ws.rs.core.SecurityContext securityContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext getSecurityContext() {
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
        public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<java.util.Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, Boolean> getProperties() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setProperty(String name, Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntityStream(java.io.InputStream entityStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getEntityStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void suspend() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void suspend(long timeout, java.util.concurrent.TimeUnit timeUnit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(long timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(java.util.Date timeout) {
            throw new UnsupportedOperationException();
        }
    }
}
