/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.mail.send;

import java.util.List;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.trust.RecipientConsentStore;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.SuppressionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The consent-invite send path (saiku#1811, PR4 — the send gate). Sends the single "please confirm you
 * want our mail" double-opt-in invite to an allowlisted address.
 *
 * <p><b>The ONE consent-exempt send — and only that.</b> Every other outbound path requires CONFIRMED
 * consent (via {@link org.saiku.service.mail.trust.RecipientGate}). The invite is the bootstrap: it is
 * the ONLY mail permitted to a not-yet-confirmed address, precisely so the recipient can confirm. To
 * keep that exemption safe it is fenced tightly:
 *
 * <ul>
 *   <li><b>Master switch (default OFF).</b> {@link #invite} refuses (sends nothing) unless {@link
 *       MailSendPolicy#isSendToOthersEnabled()} — same fail-closed flag as {@link
 *       MultiRecipientMailService}.
 *   <li><b>Allowlist-gated.</b> The address MUST be on the {@link RecipientTrustStore} allowlist; a
 *       non-allowlisted address is refused (no invite).
 *   <li><b>Never to a suppressed address.</b> {@link SuppressionStore} is a hard veto — a suppressed
 *       (unsubscribed) address is never invited, so the invite can't be used to re-mail someone who
 *       opted out.
 *   <li><b>Server-constant content.</b> The subject and body are compile-time constants (aside from the
 *       confirm link the server itself mints) — there is NO client-controlled content, closing the
 *       header/body-injection surface.
 *   <li><b>Rate-limited</b> by the REST layer with its OWN limiter (separate from the multi-send
 *       limiter), mirroring the other mail endpoints.
 * </ul>
 *
 * <p>On success it calls {@link RecipientConsentStore#requestConsent(String)} to mint the raw token
 * (recording the address PENDING), builds the confirm link, and sends ONE invite to that address only.
 * The raw token appears only inside the link in the mail body; it is never logged.
 */
public class ConsentInviteService {

    private static final Logger log = LoggerFactory.getLogger(ConsentInviteService.class);

    /** Fixed invite subject — no client input (closes the header-injection surface). */
    public static final String INVITE_SUBJECT = "Confirm you'd like to receive Saiku reports";

    private final MailSendPolicy policy;
    private final RecipientTrustStore trustStore;
    private final SuppressionStore suppressionStore;
    private final RecipientConsentStore consentStore;
    private final MailLinkBuilder linkBuilder;

    public ConsentInviteService(
            MailSendPolicy policy,
            RecipientTrustStore trustStore,
            SuppressionStore suppressionStore,
            RecipientConsentStore consentStore,
            MailLinkBuilder linkBuilder) {
        this.policy = policy;
        this.trustStore = trustStore;
        this.suppressionStore = suppressionStore;
        this.consentStore = consentStore;
        this.linkBuilder = linkBuilder;
    }

    /** True iff the ops master switch is ON. */
    public boolean isEnabled() {
        return policy != null && policy.isSendToOthersEnabled();
    }

    /** The outcome of an invite attempt (fail-closed reasons carry no address). */
    public enum Outcome {
        SENT,
        DISABLED,
        NOT_ALLOWLISTED,
        SUPPRESSED,
        INVALID_ADDRESS,
        LINK_UNCONFIGURED,
        SEND_FAILED
    }

    /**
     * Send a consent invite to {@code address}. Fail-closed at every step; sends AT MOST one message,
     * to {@code address} only.
     *
     * @param sender the configured transport.
     * @param from the server-owned From address.
     * @param address the admin-supplied recipient to invite.
     * @return the {@link Outcome} — {@code SENT} only when an invite actually went out.
     */
    public Outcome invite(MailSender sender, String from, String address) {
        // (1) MASTER SWITCH — fail closed.
        if (!isEnabled()) {
            return Outcome.DISABLED;
        }
        if (sender == null || !sender.isConfigured() || from == null || from.isBlank()) {
            throw new MailException("mail sender is not configured");
        }
        // (2) SUPPRESSION is the top veto — never invite someone who unsubscribed. Checked before the
        // allowlist so a re-added allowlist entry can't override an unsubscribe.
        if (suppressionStore != null && suppressionStore.isSuppressed(address)) {
            log.info("Consent invite refused: address is suppressed");
            return Outcome.SUPPRESSED;
        }
        // (3) ALLOWLIST — the invite is allowlist-gated (but consent-exempt).
        if (trustStore == null || !trustStore.isAllowed(address)) {
            log.info("Consent invite refused: address is not on the allowlist");
            return Outcome.NOT_ALLOWLISTED;
        }
        // (4) Mint the consent token + record PENDING. A malformed address yields a null token.
        String token = consentStore == null ? null : consentStore.requestConsent(address);
        if (token == null) {
            log.info("Consent invite refused: invalid address");
            return Outcome.INVALID_ADDRESS;
        }
        // (5) Build the confirm link. Fail-closed when the public base URL isn't configured — an invite
        // with no working link is useless and phishing-shaped, so we refuse rather than send it.
        String confirmUrl = linkBuilder == null ? null : linkBuilder.confirmUrl(address, token);
        if (confirmUrl == null) {
            log.warn("Consent invite refused: public base URL (SAIKU_PUBLIC_BASE_URL) is not configured");
            return Outcome.LINK_UNCONFIGURED;
        }
        // (6) Server-constant body (only the server-minted confirm link is interpolated).
        MailMessage msg =
                new MailMessage(address, from, INVITE_SUBJECT, inviteBody(confirmUrl), List.of(), List.of(), null);
        try {
            sender.send(msg);
            log.info("Consent invite sent"); // never log the address or token
            return Outcome.SENT;
        } catch (RuntimeException e) {
            log.warn("Consent invite send failed ({})", e.getMessage());
            return Outcome.SEND_FAILED;
        }
    }

    /**
     * The fixed invite HTML body with the server-minted confirm link interpolated. The link is
     * HTML-attribute-escaped defensively even though it is server-built from a configured base URL plus
     * a base64 token (no user content) — belt and braces against any future change to link inputs.
     */
    static String inviteBody(String confirmUrl) {
        String href = escapeHtml(confirmUrl);
        return "<p>Hello,</p>"
                + "<p>An administrator would like to send you analytics reports from Saiku. "
                + "To confirm you're happy to receive these emails, please click the link below:</p>"
                + "<p><a href=\"" + href + "\">Confirm my email address</a></p>"
                + "<p>If you did not expect this email, you can simply ignore it — no reports will be sent "
                + "unless you confirm.</p>";
    }

    /** Minimal HTML-attribute escaping (defensive; the URL is server-built). */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
