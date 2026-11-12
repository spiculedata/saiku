/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.saiku.service.user.AccountStatus;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.authorisation.AuthorisationPredicate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Enforces the managed user store's enabled flag and roles on top of whatever
 * {@code AuthenticationProvider} vouched for the account (saiku#2345).
 *
 * <p>Saiku's bundled authenticator reads {@code users.properties}, while the admin console and SCIM
 * write the H2 {@code USERS}/{@code USER_ROLES} tables, so a deactivated or demoted user kept logging
 * in with the old authorities. This policy closes that gap for accounts that exist in both:
 * <ul>
 *   <li>a disabled row denies the account ({@link #evaluate} returns {@code null});
 *   <li>a row that no longer lists an admin role strips the admin authorities from the request.
 * </ul>
 * Accounts with no row are left alone, and only credential logins
 * ({@link UsernamePasswordAuthenticationToken}: form and HTTP Basic) are considered, so share/embed/OAuth
 * principals, which have their own owner checks, are untouched.
 *
 * <p>Row lookups are cached for a couple of seconds so revocation lands on the next request without a
 * table scan per request.
 *
 * <p>As an {@link AuthorisationPredicate} it also refuses a disabled account at form login.
 */
public class AccountStatusPolicy implements AuthorisationPredicate {

    static final long DEFAULT_TTL_MS = 2_000L;

    private volatile Function<String, AccountStatus> lookup = name -> AccountStatus.UNMANAGED;
    private volatile Supplier<List<String>> adminRoles = List::of;
    private final long ttlMs;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AccountStatusPolicy(Function<String, AccountStatus> lookup, List<String> adminRoles, long ttlMs) {
        this.lookup = lookup;
        List<String> fixed = adminRoles == null ? List.of() : List.copyOf(adminRoles);
        this.adminRoles = () -> fixed;
        this.ttlMs = ttlMs;
    }

    /**
     * Spring wiring: setter-injected (not constructor-injected) because the user service and the session
     * service reference each other, so the policy must be instantiable before either is complete. The
     * user service is only called at request time.
     */
    public AccountStatusPolicy() {
        this.ttlMs = DEFAULT_TTL_MS;
    }

    public void setUserService(UserService userService) {
        this.lookup = userService::accountStatus;
        this.adminRoles = () -> {
            List<String> roles = userService.getAdminRoles();
            return roles == null ? List.of() : roles;
        };
    }

    /**
     * @return the authentication to continue with: the same instance when nothing changes, a copy without
     *     the removed admin authorities for a demoted user, or {@code null} when the account is disabled
     */
    public Authentication evaluate(Authentication auth) {
        if (!(auth instanceof UsernamePasswordAuthenticationToken) || !auth.isAuthenticated()) {
            return auth;
        }
        AccountStatus status = statusOf(auth.getName());
        if (!status.managed()) {
            return auth;
        }
        if (!status.enabled()) {
            return null;
        }
        List<String> adminRoles = this.adminRoles.get();
        if (adminRoles.stream().anyMatch(status.roles()::contains)) {
            return auth;
        }
        List<GrantedAuthority> kept = new ArrayList<>();
        boolean removedAny = false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (adminRoles.contains(ga.getAuthority())) {
                removedAny = true;
            } else {
                kept.add(ga);
            }
        }
        if (!removedAny) {
            return auth;
        }
        UsernamePasswordAuthenticationToken reduced =
                UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), auth.getCredentials(), kept);
        reduced.setDetails(auth.getDetails());
        return reduced;
    }

    @Override
    public boolean isAuthorised(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && evaluate(authentication) != null;
    }

    private AccountStatus statusOf(String username) {
        if (username == null) {
            return AccountStatus.UNMANAGED;
        }
        if (ttlMs <= 0) {
            return lookup.apply(username);
        }
        String key = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && now < cached.expiresAtMs) {
            return cached.status;
        }
        AccountStatus fresh = lookup.apply(username);
        cache.put(key, new Cached(fresh, now + ttlMs));
        return fresh;
    }

    private record Cached(AccountStatus status, long expiresAtMs) {}
}
