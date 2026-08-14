/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import java.util.List;

/**
 * Read-safe projection of the recipient allowlist for the admin GET (saiku#1811, PR1). A distinct
 * type from {@link RecipientTrustFile} — the on-disk POJO is deliberately not the API response type,
 * matching the {@link org.saiku.service.mail.MailConfigView} pattern.
 *
 * <p>There are no secrets in the allowlist, so — unlike the SMTP-password view — nothing is redacted
 * out; the value of the type is keeping the API response decoupled from the on-disk shape and
 * carrying the derived {@link #count} for the wizard. The lists are the normalised (lowercase,
 * validated) values as stored.
 */
public record RecipientTrustView(List<String> addresses, List<String> domains, int count) {}
