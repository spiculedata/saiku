/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * Produces the bytes an {@link org.saiku.service.export.destination.ExportDestination} will deliver
 * (saiku#1987) — the "what" half of the job, with the destination SPI supplying the "where".
 *
 * <p>A seam so the delivery job can be tested without a Mondrian connection, and so a future
 * producer (a rendered dashboard PDF, a scheduled XLSX) drops in beside the saved-query CSV producer
 * without touching the handler or any destination.
 */
public interface ExportArtifactProducer {

    /**
     * The producer name used in a job payload's {@code source.type}, e.g. {@code SAVED_QUERY_CSV}.
     * Lower snake case so it reads naturally in JSON.
     */
    String type();

    /**
     * Produce the artifact described by {@code spec}.
     *
     * @throws ExportDeliveryException with a sanitized message on any failure (missing saved query,
     *     query error, empty result)
     */
    ExportArtifact produce(ExportSourceSpec spec) throws ExportDeliveryException;
}
