/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.schedule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OwnerIdentityResolver} backed by the authoritative {@link UserService} (saiku#1809, PR3).
 * Looks the owner up by username in the user store and, if present, re-reads its CURRENT roles live —
 * so a role an admin revoked between job-create and job-run is absent from the resolved identity.
 *
 * <p><b>Fail-closed.</b> An unknown username, a blank username, or any lookup error resolves to
 * {@link OwnerIdentity#absent()} — the runner then refuses to run and auto-disables the job. This
 * resolver never returns a best-effort grant.
 *
 * <p><b>PR3 scope note.</b> Today's {@link UserService} / {@code JdbcUserDAO} surface exposes user
 * existence and roles keyed by username, but does NOT surface the account's {@code enabled} flag by
 * username ({@code SaikuUser} carries no enabled field). This resolver therefore treats "present in
 * the user store" as the existence gate; wiring a true enabled-state check is a PR4 concern (the seam
 * is designed for it — {@link OwnerIdentity#absent()} already means "gone OR disabled"). PR3 remains
 * runtime-dormant regardless: no Spring bean instantiates this yet.
 */
public final class UserServiceOwnerIdentityResolver implements OwnerIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(UserServiceOwnerIdentityResolver.class);

    private final UserService userService;

    public UserServiceOwnerIdentityResolver(UserService userService) {
        if (userService == null) {
            throw new IllegalArgumentException("userService is required");
        }
        this.userService = userService;
    }

    @Override
    public OwnerIdentity resolve(String username) {
        if (username == null || username.isBlank()) {
            return OwnerIdentity.absent();
        }
        try {
            SaikuUser owner = null;
            for (SaikuUser u : userService.getUsers()) {
                if (u != null && username.equals(u.getUsername())) {
                    owner = u;
                    break;
                }
            }
            if (owner == null) {
                log.debug("Owner '{}' not found in user store — resolving absent (fail-closed)", username);
                return OwnerIdentity.absent();
            }
            // Re-read CURRENT roles live — never a stale snapshot. getRoles reflects the store as of
            // now, so a revoked role is already gone.
            String[] current = userService.getRoles(owner);
            List<String> roles = current == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(current));
            return OwnerIdentity.present(roles);
        } catch (Exception e) {
            // Any failure resolving the owner is treated as "unavailable" — fail closed, never run.
            log.warn("Failed to resolve owner '{}' — resolving absent (fail-closed): {}", username, e.toString());
            return OwnerIdentity.absent();
        }
    }
}
