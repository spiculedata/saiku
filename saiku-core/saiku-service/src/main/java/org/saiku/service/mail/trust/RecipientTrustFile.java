/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed representation of {@code ${saiku.home}/mail-recipients.json} — the admin-writable recipient
 * allowlist for the recipient-trust model (saiku#1811, PR1). Every persisted field has an explicit
 * Java field so a round-trip through Jackson never silently drops config (the dashboard-save
 * drop-on-reload lesson).
 *
 * <p><b>DORMANT infrastructure.</b> In PR1 this store is intentionally not consulted by any send
 * path. It merely records the admin's intended allowlist so later phases (a {@code RecipientGate},
 * consent, multi-recipient send) can enforce it. Nothing here grants any new send capability, and the
 * anti-relay boundary (recipient is only ever the server's own {@code selfTo}) is unchanged.
 *
 * <p>The allowlist has two parts:
 *
 * <ul>
 *   <li>{@link #addresses} — explicit, fully-qualified email addresses (normalised lowercase).
 *   <li>{@link #domains} — domain suffixes (the host part only, e.g. {@code acme.com}, normalised
 *       lowercase, no leading {@code @}). Matching is EXACT host-suffix: {@code acme.com} matches
 *       {@code x@acme.com} but NOT {@code x@evil-acme.com}.
 * </ul>
 *
 * <p>Unknown properties are ignored so a newer on-disk file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecipientTrustFile {

    /** Explicit, fully-qualified allowed addresses (normalised lowercase). */
    private List<String> addresses = new ArrayList<>();

    /** Allowed domain hosts (normalised lowercase, no leading {@code @}). Exact host-suffix match. */
    private List<String> domains = new ArrayList<>();

    public RecipientTrustFile() {}

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses == null ? new ArrayList<>() : addresses;
    }

    public List<String> getDomains() {
        return domains;
    }

    public void setDomains(List<String> domains) {
        this.domains = domains == null ? new ArrayList<>() : domains;
    }
}
