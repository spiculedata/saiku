/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1752 — test helper that seeds the current thread's {@link SecurityContextHolder} with a
 * username + roles, mirroring how the runtime carries authorities. Resources now read roles
 * authoritatively from the holder (see {@code SessionRoles} / saiku#1747), so unit tests must seed
 * the holder rather than the session {@code "roles"} map.
 */
public final class RoleTestSupport {

    private RoleTestSupport() {}

    /** Install an authenticated principal with {@code roles} as granted authorities. */
    public static void authenticate(String username, List<String> roles) {
        List<GrantedAuthority> auths = new ArrayList<>();
        if (roles != null) {
            for (String r : roles) {
                if (r != null) {
                    auths.add(new SimpleGrantedAuthority(r));
                }
            }
        }
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(username == null ? "anonymous" : username, null, auths);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** Clear the holder — call from {@code @After} so tests don't bleed authorities into each other. */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
