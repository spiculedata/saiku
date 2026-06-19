/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.token;

import java.util.List;

/**
 * Immutable principal produced by a successful {@link TokenValidator} call.
 * Carries the username and the list of Spring Security role strings (e.g.
 * {@code "ROLE_USER"}) that the bearer token grants.
 */
public record AuthedPrincipal(String username, List<String> roles) {

    public AuthedPrincipal(String username, List<String> roles) {
        this.username = username;
        this.roles = List.copyOf(roles);
    }
}
