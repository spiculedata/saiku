/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import java.util.List;

/**
 * Read-safe projection of the suppression list for an admin view (saiku#1811, PR2). A distinct type
 * from {@link SuppressionFile} — the on-disk POJO is deliberately not the API response type, matching
 * the {@link RecipientTrustView} pattern.
 *
 * <p>The suppression list is low-sensitivity (it is the set of people who asked NOT to be mailed), but
 * to avoid a bulk PII dump the {@link #entries} carry only a masked address (local part reduced to its
 * first character, e.g. {@code a***@acme.com}) plus the coarse reason and timestamp. The derived
 * {@link #count} is the total.
 */
public record SuppressionView(List<Entry> entries, int count) {

    /** One redacted suppression row: masked address, coarse reason, epoch-millis timestamp. */
    public record Entry(String maskedAddress, String reason, long timestamp) {}
}
