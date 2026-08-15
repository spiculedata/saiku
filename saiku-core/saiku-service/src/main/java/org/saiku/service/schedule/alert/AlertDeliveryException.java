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
 * A threshold-alert channel failed to deliver (saiku#1098). Carries a short, sanitized message only —
 * never a secret, never a stack trace fragment — so the engine can record it on the job's {@code
 * lastError} safely.
 */
public class AlertDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;

    public AlertDeliveryException(String message) {
        super(message);
    }

    public AlertDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
