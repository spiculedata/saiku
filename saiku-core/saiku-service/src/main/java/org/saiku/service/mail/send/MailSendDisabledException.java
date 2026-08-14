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

/**
 * Thrown by the non-self send paths ({@link MultiRecipientMailService}, {@link ConsentInviteService})
 * when the default-OFF {@link MailSendPolicy} master flag is not enabled (saiku#1811, PR4).
 *
 * <p>Its presence proves fail-closed behaviour: the send methods throw BEFORE composing or transmitting
 * anything, so with the flag OFF no mail can reach a non-self address. The REST layer maps it to a
 * {@code 403 FORBIDDEN} ("non-self send is disabled").
 */
public class MailSendDisabledException extends RuntimeException {

    public MailSendDisabledException() {
        super("non-self send is disabled (saiku.mail.sendToOthers.enabled is off)");
    }
}
