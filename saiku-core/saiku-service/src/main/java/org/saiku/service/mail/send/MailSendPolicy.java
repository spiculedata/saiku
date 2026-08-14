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

import java.util.function.Function;

/**
 * The default-OFF, ops-only feature flag that gates the ENTIRE non-self-send capability (saiku#1811,
 * PR4 — the send gate).
 *
 * <p><b>This is the top-priority safety control of the whole distribution track.</b> Every code path
 * that could mail a non-self address ({@link MultiRecipientMailService}, {@link ConsentInviteService})
 * consults {@link #isSendToOthersEnabled()} FIRST and refuses (fails closed) when it is OFF. With the
 * flag OFF — the default — no path can mail a non-self address and the application behaves exactly as
 * before this PR: the server's {@code /email/self} and admin test-send paths remain {@code selfTo}-only
 * and are completely unaffected by this flag.
 *
 * <p><b>Ops-only.</b> The flag is read from the environment variable
 * {@code SAIKU_MAIL_SEND_TO_OTHERS_ENABLED} or the system property
 * {@code saiku.mail.sendToOthers.enabled} — the same env/prop discipline as {@link
 * org.saiku.service.mail.MailConfig}. It is NEVER settable from a request body, an admin wizard, or any
 * in-app surface: flipping it on is a deliberate deployment act. There is no way to enable non-self send
 * from HTTP.
 *
 * <p><b>Defense-in-depth, not the whole defense.</b> Enabling the flag does NOT bypass the {@link
 * org.saiku.service.mail.trust.RecipientGate}: even with the flag ON, every recipient must still pass
 * the gate (not suppressed ∧ allowlisted ∧ consent CONFIRMED). The flag is an additional master switch
 * ON TOP OF the gate and the human merge gate, so a merge of this PR changes nothing at runtime until an
 * admin explicitly turns it on.
 */
public final class MailSendPolicy {

    /** Ops env var (highest precedence). */
    public static final String ENV_KEY = "SAIKU_MAIL_SEND_TO_OTHERS_ENABLED";

    /** Ops system property (fallback). */
    public static final String PROP_KEY = "saiku.mail.sendToOthers.enabled";

    private final Function<String, String> env;
    private final Function<String, String> prop;

    /** Production ctor: read from the real environment + system properties. */
    public MailSendPolicy() {
        this(System::getenv, System::getProperty);
    }

    /** Injectable seam so tests can drive the flag without touching the real environment. */
    public MailSendPolicy(Function<String, String> env, Function<String, String> prop) {
        this.env = env;
        this.prop = prop;
    }

    /**
     * The master switch for non-self send. {@code true} ONLY when an admin has explicitly set the env
     * var or system property to a truthy value; {@code false} for unset / blank / any non-true value.
     * Fail-closed: anything that is not an explicit {@code "true"} keeps non-self send OFF.
     */
    public boolean isSendToOthersEnabled() {
        String v = env.apply(ENV_KEY);
        if (v == null || v.isBlank()) {
            v = prop.apply(PROP_KEY);
        }
        if (v == null) {
            return false;
        }
        // Only a literal "true" (case-insensitive, trimmed) enables it. Everything else -> OFF.
        return Boolean.parseBoolean(v.trim());
    }
}
