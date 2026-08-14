/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

/**
 * Double-opt-in consent lifecycle for a recipient (saiku#1811, PR3).
 *
 * <ul>
 *   <li>{@link #PENDING} — an admin requested consent; an invite token was minted and (in a later PR)
 *       will be mailed. The recipient has NOT yet confirmed. A gate treats this as NOT confirmed.
 *   <li>{@link #CONFIRMED} — the recipient clicked their own confirm link with a valid, unexpired
 *       token. This is the ONLY state a {@code RecipientGate} treats as consented.
 *   <li>{@link #REVOKED} — consent was withdrawn (admin action, or a future unsubscribe wiring). Never
 *       treated as confirmed.
 * </ul>
 */
public enum ConsentStatus {
    PENDING,
    CONFIRMED,
    REVOKED
}
