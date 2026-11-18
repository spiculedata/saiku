/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.user;

import java.util.Set;

/**
 * What the managed user store (H2 {@code USERS} / {@code USER_ROLES}, written by the admin console
 * and SCIM) says about an account (saiku#2345).
 *
 * @param managed true when the store has a row for the username; accounts that exist only in the
 *     authenticator (users.properties, LDAP, ...) are unmanaged and are never second-guessed
 * @param enabled the {@code USERS.ENABLED} flag
 * @param roles the {@code USER_ROLES} entries
 */
public record AccountStatus(boolean managed, boolean enabled, Set<String> roles) {

    /** No row in the managed store: leave the authenticator's answer alone. */
    public static final AccountStatus UNMANAGED = new AccountStatus(false, true, Set.of());

    public AccountStatus {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
