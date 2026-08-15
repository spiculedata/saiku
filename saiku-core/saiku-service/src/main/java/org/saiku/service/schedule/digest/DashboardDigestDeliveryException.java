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
package org.saiku.service.schedule.digest;

/**
 * Thrown when a {@code DASHBOARD_DIGEST} job cannot deliver its email (saiku#943) — e.g. no transport
 * configured, or the self-address fallback is unset. The scheduler records a short sanitized FAILED
 * outcome; the message must never carry a credential, token or recipient address.
 */
public class DashboardDigestDeliveryException extends Exception {

    public DashboardDigestDeliveryException(String message) {
        super(message);
    }

    public DashboardDigestDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
