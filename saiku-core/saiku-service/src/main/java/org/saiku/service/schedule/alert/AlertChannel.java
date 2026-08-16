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
package org.saiku.service.schedule.alert;

/**
 * Delivers a fired {@link AlertEvent} over one channel (saiku#1098). Two implementations exist, both
 * scheduler-only: {@link WebhookAlertChannel} (admin-authored, SSRF-validated URL) and {@link
 * SelfEmailAlertChannel} (the server's own self-address). <b>Neither touches the send-to-others gate
 * or the renderer.</b>
 */
public interface AlertChannel {

    /**
     * Deliver {@code event} using {@code config}.
     *
     * @throws Exception on any delivery failure — propagated to the engine, which records a sanitized
     *     outcome and applies backoff. Implementations must never place a secret in the message.
     */
    void deliver(AlertEvent event, AlertChannelConfig config) throws Exception;
}
