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
 * The immutable description of a fired threshold breach (saiku#1098), handed to an {@link
 * AlertChannel} to deliver. Deliberately small and data-only — cube ref, measure, the observed value,
 * the threshold it crossed, the comparison, and the epoch-millis timestamp — so both the webhook JSON
 * body and the email body render from the same facts.
 *
 * @param jobId the alerting job's id (opaque, unguessable)
 * @param cube the cube reference in {@code conn/catalog/schema/cube} form
 * @param measure the measure name
 * @param comparison the comparison that triggered the breach
 * @param value the observed current value
 * @param previousValue the prior observation for change comparisons, or {@code null}
 * @param threshold the configured threshold
 * @param timestamp epoch millis when the breach was detected
 */
public record AlertEvent(
        String jobId,
        String cube,
        String measure,
        AlertComparison comparison,
        double value,
        Double previousValue,
        double threshold,
        long timestamp) {}
