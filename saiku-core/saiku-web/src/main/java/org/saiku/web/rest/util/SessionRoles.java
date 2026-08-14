/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.saiku.web.rest.util;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#1752 — the single, authoritative reader for the current request's roles.
 *
 * <p>Follow-up to saiku#1747, which moved {@code UserService.getCurrentUserRolesList()}/{@code
 * isAdmin()} off the lazily-seeded session {@code "roles"} map and onto Spring's {@link
 * SecurityContextHolder}, the deterministic per-request source. That fix left ~10 JAX-RS resources
 * still reading {@code sessionService.getAllSessionObjects().get("roles")} directly, each with a
 * near-duplicate {@code currentRoles()} helper. This class collapses them onto one implementation
 * that reads the holder authorities exactly the way {@code UserService} does, so no role read can
 * drift from any other.
 *
 * <p><b>Why this is a consistency fix and not a behaviour change:</b> the session {@code "roles"}
 * entry is only ever written FROM {@code SecurityContextHolder} authorities — {@code
 * SessionService.createSession} and {@code SessionService.runAs} are the only writers and both copy
 * {@code getAuthorities()} verbatim. There is no session-only role shape, so a real authenticated
 * user resolves to the SAME set here as via the session; the holder read is just always-fresh
 * rather than lazily/conditionally seeded.
 *
 * <p><b>Impersonation / delegated execution (saiku#941):</b> {@code SessionService.runAs} sets BOTH
 * the {@code SecurityContextHolder} authorities AND the session map to the same delegated role list
 * for the duration of the call, so reading the holder yields exactly the impersonated roles — the
 * share-link guest path is preserved.
 *
 * <p><b>Guest tokens ({@code ROLE_EMBED_GUEST} / {@code ROLE_SHARE_GUEST}):</b> those roles are
 * carried as {@code SecurityContextHolder} authorities on the guest principal, so they resolve here
 * identically.
 *
 * <p><b>Fail-closed (saiku#1516):</b> a missing / unauthenticated / anonymous principal yields an
 * EMPTY list, never null and never a grant.
 */
public final class SessionRoles {

    private SessionRoles() {}

    /**
     * The current request's granted roles, read authoritatively from {@link SecurityContextHolder}.
     *
     * @return an immutable, never-null list of role authority strings; empty when there is no
     *     authenticated, non-anonymous principal.
     */
    public static List<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        // Absent auth, unauthenticated, or an anonymous token -> no roles (fail-closed). An
        // AnonymousAuthenticationToken carries only ROLE_ANONYMOUS, never an admin/schema role, but
        // we exclude it explicitly so an anonymous principal can never contribute a role.
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga != null && ga.getAuthority() != null) {
                roles.add(ga.getAuthority());
            }
        }
        return List.copyOf(roles);
    }
}
