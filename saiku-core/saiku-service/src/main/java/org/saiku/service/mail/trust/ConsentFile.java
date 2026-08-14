/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed representation of {@code ${saiku.home}/mail-consent.json} — the double-opt-in consent store
 * for the recipient-trust model (saiku#1811, PR3). Every persisted field has an explicit Java field so
 * a round-trip through Jackson never silently drops data (the dashboard-save drop-on-reload lesson).
 *
 * <p><b>Fail-closed by construction.</b> Only a recipient whose entry is {@link ConsentStatus#CONFIRMED}
 * counts as consented. An absent entry, PENDING, REVOKED, or an expired token all read as NOT confirmed,
 * so a {@code RecipientGate} denies. Nothing here grants any send capability, and the anti-relay
 * boundary (recipient is only ever the server's own {@code selfTo}) is unchanged in this PR.
 *
 * <p>Unknown properties are ignored so a newer on-disk file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConsentFile {

    /** The consent records (address normalised lowercase, status, timestamps, salted token hash). */
    private List<ConsentEntry> entries = new ArrayList<>();

    public ConsentFile() {}

    public List<ConsentEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ConsentEntry> entries) {
        this.entries = entries == null ? new ArrayList<>() : entries;
    }
}
