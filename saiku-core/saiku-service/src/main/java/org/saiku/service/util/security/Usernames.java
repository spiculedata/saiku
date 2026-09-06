/*
 *   Copyright 2026 Spicule Ltd
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
package org.saiku.service.util.security;

import java.util.Locale;

/**
 * The single canonicalisation point for Saiku user identity (saiku#1907, CWE-178).
 *
 * <p><b>Invariant this depends on:</b> the account store matches usernames
 * case-insensitively, so {@code admin} and {@code Admin} are the same account and can
 * never both exist. This holds for the shipped in-memory store (Spring Security's
 * {@code InMemoryUserDetailsManager} lower-cases its keys). External stores wired in by
 * an operator — LDAP, OAuth/OIDC, SAML, a custom JDBC {@code UserDetailsService} — could
 * in principle hold two accounts differing only in case; if so, they MUST be configured
 * case-insensitively, or two distinct people would be conflated onto one ACL/home
 * identity. Callers that create per-user state guard against that separately (see the
 * home-folder conflict check in {@code FilesystemRepositoryManager.createUser}).
 *
 * <p>Identity used for ACL ownership and the {@code /homes/<user>} path is canonicalised
 * through {@link #canonicalize(String)}; identity forwarded as warehouse credentials
 * (datasource pass-through) keeps its ORIGINAL spelling and must NOT be canonicalised.
 */
public final class Usernames {

    private Usernames() {}

    /**
     * Canonical identity form for ACL/home comparison: lower-cased with {@link Locale#ROOT}
     * (locale-independent, so it behaves identically on every server). Null-safe: a null
     * input yields null.
     */
    public static String canonicalize(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    /**
     * Case-insensitive identity equality via the single {@link #canonicalize(String)} form.
     * Two null inputs are NOT equal (an absent identity never matches anything).
     */
    public static boolean sameUser(String a, String b) {
        String ca = canonicalize(a);
        return ca != null && ca.equals(canonicalize(b));
    }
}
