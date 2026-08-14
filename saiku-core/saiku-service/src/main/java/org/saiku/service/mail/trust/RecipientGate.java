/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The SINGLE fail-closed chokepoint every future outbound send to a non-self recipient must pass
 * (saiku#1811, PR3). It consults the three trust stores and returns a pure {@link Decision} — it
 * performs NO send and NO network I/O. It is the gate that PR4's send layer will call before mailing
 * anyone but the server's own {@code selfTo}.
 *
 * <p><b>Order of checks (all must pass; any failure → {@link Decision#DENY}, no override):</b>
 *
 * <ol>
 *   <li><b>Normalise + RFC-validate</b> the address; a null/blank/malformed address is DENIED.
 *   <li><b>NOT suppressed</b> ({@link SuppressionStore#isSuppressed}) — checked FIRST, a hard veto that
 *       outranks allowlist AND consent. If someone unsubscribed, no allowlist or consent state can
 *       re-enable mail to them.
 *   <li><b>Allowlisted</b> ({@link RecipientTrustStore#isAllowed}).
 *   <li><b>Consent CONFIRMED</b> ({@link RecipientConsentStore#isConfirmed}).
 * </ol>
 *
 * <p><b>Fail-closed everywhere.</b> Only {@code (not suppressed) ∧ allowlisted ∧ CONFIRMED} yields
 * {@link Decision#ALLOW}. An empty / absent / corrupt store makes its check fail, which denies the
 * address. Empty stores therefore allow NOBODY. There is no exception path, no override flag, and no
 * "trusted admin" bypass — the gate is the same for every caller.
 *
 * <p><b>DORMANT — no send-path caller.</b> As of PR3 nothing calls this gate: there is no send path to
 * non-self recipients, and none is added here. It is decision-only infrastructure for #1811 PR4. The
 * anti-relay boundary (recipient is only ever the server's own {@code selfTo}) is unchanged.
 */
public class RecipientGate {

    private final SuppressionStore suppressionStore;
    private final RecipientTrustStore trustStore;
    private final RecipientConsentStore consentStore;

    public RecipientGate(
            SuppressionStore suppressionStore, RecipientTrustStore trustStore, RecipientConsentStore consentStore) {
        this.suppressionStore = suppressionStore;
        this.trustStore = trustStore;
        this.consentStore = consentStore;
    }

    /** The gate outcome for a single address. */
    public enum Decision {
        ALLOW,
        DENY
    }

    /**
     * Decide whether {@code address} may be mailed. Fail-closed: any check that cannot pass (including a
     * missing store) yields {@link Decision#DENY}. Pure — no send, no network.
     */
    public Decision decide(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return Decision.DENY; // malformed/blank -> deny
        }
        // (b) Suppression is the top veto — checked FIRST, outranks allowlist AND consent.
        if (suppressionStore == null || suppressionStore.isSuppressed(normalised)) {
            return Decision.DENY;
        }
        // (c) Must be explicitly allowlisted.
        if (trustStore == null || !trustStore.isAllowed(normalised)) {
            return Decision.DENY;
        }
        // (d) Must have CONFIRMED double-opt-in consent.
        if (consentStore == null || !consentStore.isConfirmed(normalised)) {
            return Decision.DENY;
        }
        return Decision.ALLOW;
    }

    /** Convenience: {@code decide(address) == ALLOW}. */
    public boolean isAllowed(String address) {
        return decide(address) == Decision.ALLOW;
    }

    /**
     * Filter {@code addresses} down to only the survivors that {@link #decide} ALLOWs, preserving order
     * and de-duplicating on the normalised address. A null collection yields an empty list. This is the
     * method PR4's send layer will call to clear a multi-recipient list before sending — every dropped
     * address is silently denied (fail-closed), never mailed.
     */
    public List<String> clear(Collection<String> addresses) {
        List<String> out = new ArrayList<>();
        if (addresses == null) {
            return out;
        }
        List<String> seen = new ArrayList<>();
        for (String address : addresses) {
            String normalised = normaliseAddress(address);
            if (normalised == null || seen.contains(normalised)) {
                continue;
            }
            if (decide(normalised) == Decision.ALLOW) {
                seen.add(normalised);
                out.add(normalised);
            }
        }
        return out;
    }

    // ---- normalisation (pure; matches the sibling stores) ----

    /** Lowercase + CRLF-strip + RFC-validate an address; null when blank/invalid. */
    private static String normaliseAddress(String address) {
        if (address == null) {
            return null;
        }
        String s = address.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.toLowerCase();
        try {
            InternetAddress ia = new InternetAddress(s, true);
            ia.validate();
            String bare = ia.getAddress();
            return bare == null ? null : bare.toLowerCase();
        } catch (AddressException e) {
            return null;
        }
    }
}
