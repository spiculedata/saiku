/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.token;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class BearerTokenAuthFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest req(String authz) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("Authorization")).thenReturn(authz);
        when(r.getRequestURI()).thenReturn("/rest/saiku/api/ai/cubes");
        when(r.getContextPath()).thenReturn("");
        return r;
    }

    @Test
    void setsContextForValidBearer() throws Exception {
        TokenValidator v = t ->
                t.equals("good") ? Optional.of(new AuthedPrincipal("alice", List.of("ROLE_USER"))) : Optional.empty();
        BearerTokenAuthFilter f = new BearerTokenAuthFilter(v);
        FilterChain chain = mock(FilterChain.class);
        final String[] name = new String[1];
        doAnswer(i -> {
                    name[0] = SecurityContextHolder.getContext()
                            .getAuthentication()
                            .getName();
                    return null;
                })
                .when(chain)
                .doFilter(any(), any());
        f.doFilter(req("Bearer good"), mock(HttpServletResponse.class), chain);
        assertEquals("alice", name[0]);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void passesThroughWhenNoBearer() throws Exception {
        BearerTokenAuthFilter f = new BearerTokenAuthFilter(t -> Optional.empty());
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req(null), mock(HttpServletResponse.class), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void passesThroughWhenInvalidBearer() throws Exception {
        BearerTokenAuthFilter f = new BearerTokenAuthFilter(t -> Optional.empty());
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req("Bearer nope"), mock(HttpServletResponse.class), chain);
        verify(chain).doFilter(any(), any());
    }
}
