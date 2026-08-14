/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed representation of {@code ${saiku.home}/mail-suppression.json} — the recipient suppression
 * list for the recipient-trust model (saiku#1811, PR2). Every persisted field has an explicit Java
 * field so a round-trip through Jackson never silently drops data (the dashboard-save
 * drop-on-reload lesson).
 *
 * <p><b>Fail-SAFE by construction.</b> The suppression list can only ever REDUCE who may be mailed —
 * it is the future top-veto for a {@code RecipientGate} (saiku#1811 PR3). Nothing here grants any
 * send capability, and the anti-relay boundary (recipient is only ever the server's own
 * {@code selfTo}) is unchanged in this PR.
 *
 * <p>Unknown properties are ignored so a newer on-disk file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuppressionFile {

    /** The suppressed entries (address normalised lowercase, coarse reason, timestamp). */
    private List<SuppressionEntry> entries = new ArrayList<>();

    public SuppressionFile() {}

    public List<SuppressionEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<SuppressionEntry> entries) {
        this.entries = entries == null ? new ArrayList<>() : entries;
    }
}
