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
package org.saiku.web.email;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /saiku/admin/mail-send} (saiku#1811, PR4) — a multi-recipient send. The
 * {@code recipients} list is the admin's intended audience; every entry is passed through the fail-closed
 * {@link org.saiku.service.mail.trust.RecipientGate} and ONLY the cleared survivors are mailed, each
 * individually addressed. The message body reuses the same validated/sanitized artifacts as the
 * self-send composer ({@link EmailSelfRequest}).
 */
public class MailSendRequest {

    private List<String> recipients = new ArrayList<>();
    private EmailSelfRequest message;

    public List<String> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<String> recipients) {
        this.recipients = recipients;
    }

    public EmailSelfRequest getMessage() {
        return message;
    }

    public void setMessage(EmailSelfRequest message) {
        this.message = message;
    }
}
