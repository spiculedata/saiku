/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import java.util.List;

/**
 * Read-safe projection of the consent store for an admin view (saiku#1811, PR3). A distinct type from
 * {@link ConsentFile} — the on-disk POJO is deliberately not the API response type, matching the
 * {@link SuppressionView} / {@link RecipientTrustView} pattern.
 *
 * <p>Entries carry only a masked address (local part reduced to its first character, e.g.
 * {@code a***@acme.com}), the {@link ConsentStatus}, and timestamps. The salted token hash is NEVER
 * projected — it is an internal lookup secret, not admin-facing. {@link #count} is the total.
 */
public record ConsentView(List<Entry> entries, int count) {

    /** One redacted consent row: masked address, status, request/confirm timestamps, token expiry. */
    public record Entry(
            String maskedAddress, ConsentStatus status, long requestedAt, long confirmedAt, long tokenExpiresAt) {}
}
