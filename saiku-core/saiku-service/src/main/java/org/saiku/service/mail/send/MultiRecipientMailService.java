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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.saiku.service.mail.Attachment;
import org.saiku.service.mail.InlineImage;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.trust.RecipientGate;
import org.saiku.service.mail.trust.UnsubscribeTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The multi-recipient send layer (saiku#1811, PR4 — the send gate). <b>This is the first code in Saiku
 * that can mail a non-self address.</b> Every safeguard below is load-bearing.
 *
 * <p><b>Master switch (default OFF).</b> {@link #send} refuses (fails closed, sends NOTHING) unless
 * {@link MailSendPolicy#isSendToOthersEnabled()} is true. With the flag OFF — the default — this class
 * is inert and the application behaves exactly as before this PR.
 *
 * <p><b>Gate, no override.</b> Even with the flag ON, the requested recipient list is passed through
 * {@link RecipientGate#clear(Collection)} and ONLY the cleared survivors (not suppressed ∧ allowlisted ∧
 * consent CONFIRMED) are mailed. A rejected recipient is silently dropped — never mailed, never
 * enumerated back to the caller, logged only by COUNT (never by address). There is no bypass path.
 *
 * <p><b>Individually addressed — the recipient list never leaks.</b> One separate {@link MailMessage}
 * is built and sent PER cleared recipient, each with a single {@code To:} of that recipient only. No
 * shared TO/CC/BCC is ever used, so no recipient can see any other recipient's address. Each message
 * carries its OWN per-recipient one-click {@code List-Unsubscribe} link (HMAC-signed for that address).
 *
 * <p><b>Partial success, per-recipient isolation.</b> One recipient's transport failure never aborts
 * the batch: each send is independent and its outcome recorded; the {@link Result} reports counts
 * (requested / cleared / sent / failed / dropped) with no addresses.
 *
 * <p><b>Per-batch cap.</b> The cleared list is capped at {@link #maxPerBatch} recipients before
 * sending; the overflow is dropped (counted, not mailed). The per-MINUTE rate cap lives at the REST
 * layer (an {@code AiRateLimiter} in the admin resource), mirroring the other mail endpoints.
 */
public class MultiRecipientMailService {

    private static final Logger log = LoggerFactory.getLogger(MultiRecipientMailService.class);

    /** Default hard ceiling on recipients per single batch. Overridable ops-only for tests. */
    private static final int DEFAULT_MAX_PER_BATCH = Integer.getInteger("saiku.mail.send.maxPerBatch", 50);

    private final MailSendPolicy policy;
    private final RecipientGate gate;
    private final UnsubscribeTokens unsubscribeTokens;
    private final MailLinkBuilder linkBuilder;
    private final int maxPerBatch;

    public MultiRecipientMailService(
            MailSendPolicy policy,
            RecipientGate gate,
            UnsubscribeTokens unsubscribeTokens,
            MailLinkBuilder linkBuilder) {
        this(policy, gate, unsubscribeTokens, linkBuilder, DEFAULT_MAX_PER_BATCH);
    }

    public MultiRecipientMailService(
            MailSendPolicy policy,
            RecipientGate gate,
            UnsubscribeTokens unsubscribeTokens,
            MailLinkBuilder linkBuilder,
            int maxPerBatch) {
        this.policy = policy;
        this.gate = gate;
        this.unsubscribeTokens = unsubscribeTokens;
        this.linkBuilder = linkBuilder;
        this.maxPerBatch = Math.max(1, maxPerBatch);
    }

    /** True iff the ops master switch is ON (non-self send permitted). */
    public boolean isEnabled() {
        return policy != null && policy.isSendToOthersEnabled();
    }

    /** The per-batch recipient ceiling in effect. */
    public int maxPerBatch() {
        return maxPerBatch;
    }

    /**
     * Send {@code content} to the vetted subset of {@code recipients}, one individually-addressed
     * message per cleared recipient.
     *
     * @param sender the configured transport (must be non-null and configured).
     * @param from the server-owned From address.
     * @param recipients the admin-supplied recipient references (addresses). May contain duplicates,
     *     malformed, non-allowlisted, non-confirmed, or suppressed entries — all are filtered out.
     * @param content the (server-composed or admin-composed) subject + HTML body + optional
     *     images/attachments to deliver to each cleared recipient.
     * @return per-batch counts (never addresses).
     * @throws MailSendDisabledException when the master flag is OFF (fail-closed — nothing sent).
     */
    public Result send(MailSender sender, String from, Collection<String> recipients, MailContent content) {
        // (1) MASTER SWITCH — fail closed. Nothing below runs when non-self send is disabled.
        if (!isEnabled()) {
            throw new MailSendDisabledException();
        }
        if (sender == null || !sender.isConfigured()) {
            throw new MailException("mail sender is not configured");
        }
        if (from == null || from.isBlank()) {
            throw new MailException("from address is not configured");
        }
        if (content == null) {
            throw new MailException("mail content is required");
        }

        int requested = recipients == null ? 0 : recipients.size();

        // (2) GATE — the ONLY way an address survives is by clearing suppression + allowlist + consent.
        List<String> cleared = gate == null ? List.of() : gate.clear(recipients);
        int clearedCount = cleared.size();
        int droppedByGate = requested - clearedCount;

        // (3) PER-BATCH CAP — drop the overflow (counted, not mailed).
        int droppedByCap = 0;
        if (cleared.size() > maxPerBatch) {
            droppedByCap = cleared.size() - maxPerBatch;
            cleared = new ArrayList<>(cleared.subList(0, maxPerBatch));
        }

        int sent = 0;
        int failed = 0;
        for (String recipient : cleared) {
            // (4) Per-recipient one-click List-Unsubscribe (HMAC-signed for THIS address only).
            String listUnsub = null;
            if (unsubscribeTokens != null && linkBuilder != null) {
                String token = unsubscribeTokens.tokenFor(recipient);
                listUnsub = linkBuilder.unsubscribeHeader(recipient, token);
            }
            // (5) INDIVIDUALLY ADDRESSED — a fresh message whose sole To: is this recipient. No shared
            // TO/CC/BCC ever, so the recipient list can never leak to any recipient.
            MailMessage msg = new MailMessage(
                    recipient,
                    from,
                    content.subject(),
                    content.htmlBody(),
                    content.inlineImages(),
                    content.attachments(),
                    listUnsub);
            try {
                sender.send(msg);
                sent++;
            } catch (RuntimeException e) {
                // (6) PARTIAL SUCCESS — one bad recipient never aborts the batch. Never log the address.
                failed++;
                log.warn("Multi-recipient send: one recipient failed ({})", e.getMessage());
            }
        }

        int dropped = droppedByGate + droppedByCap;
        // Counts only — never an address, never the recipient list.
        log.info(
                "Multi-recipient send complete: requested={} cleared={} sent={} failed={} dropped={}",
                requested,
                clearedCount,
                sent,
                failed,
                dropped);
        return new Result(requested, clearedCount, sent, failed, dropped);
    }

    /**
     * The subject + HTML body (+ optional inline images / attachments) delivered to each cleared
     * recipient. The same content is delivered to every recipient; only the {@code To:} and the
     * per-recipient {@code List-Unsubscribe} differ.
     */
    public record MailContent(
            String subject, String htmlBody, List<InlineImage> inlineImages, List<Attachment> attachments) {
        public MailContent {
            inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** Convenience for a plain HTML message with no images/attachments. */
        public static MailContent of(String subject, String htmlBody) {
            return new MailContent(subject, htmlBody, List.of(), List.of());
        }
    }

    /** Per-batch outcome — counts only, never addresses (no recipient-list leak, no enumeration). */
    public record Result(int requested, int cleared, int sent, int failed, int dropped) {}
}
