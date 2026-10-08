/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

/**
 * The <b>export destination SPI</b> (saiku#1987) — the file-delivery sibling of
 * {@link org.saiku.service.schedule.alert.AlertChannel}, and deliberately shaped the same way.
 *
 * <p>An exporter ({@code ExporterResource} / the scheduled {@code EXPORT_DELIVERY} job) produces
 * <em>bytes</em>; a destination decides where those bytes <em>land</em>. Anything that can accept a
 * blob — Google Drive today, OneDrive / S3 / Dropbox / a custom HTTP sink later — implements this
 * one interface and is registered in {@code saiku-beans.xml} like every other pluggable kind
 * ({@code JobHandler}, {@code AlertChannel}). That is the whole point: an installer adds a delivery
 * channel by adding a bean, not by forking the export stack.
 *
 * <p><b>This is NOT the tile-plugin surface</b> (saiku#1441). Those are sandboxed viz iframes served
 * with {@code connect-src 'none'} and physically cannot upload a file. Do not overload either API.
 *
 * <h2>Registration</h2>
 *
 * Bean's Spring {@code id} (or the {@link #id()} it declares) keys it in
 * {@link ExportDestinationRegistry}. Discovery of third-party implementations from
 * {@code saiku-home/plugins/*.jar} is explicitly <b>out of scope for the MVP</b> — see
 * {@code docs/EXPORT-DESTINATIONS.md}.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>No secret in the payload.</b> Credentials live in {@link ExportDestinationConfig}'s
 *       secret half, written only by the admin API into an owner-only file — never in a query JSON,
 *       never in a job payload.</li>
 *   <li><b>Threading.</b> {@link #deliver} runs on a scheduler worker thread (or a REST thread for
 *       the admin "test delivery" button), never on a request-bound bean. Implementations must be
 *       thread-safe.</li>
 *   <li><b>Errors.</b> Throw {@link ExportDeliveryException} with a short, <b>sanitized</b> message.
 *       The scheduler records it and applies backoff; the admin API shows it. Never put a token, a
 *       private key, a refresh token or a response body full of them into the message.</li>
 *   <li><b>Idempotence is best-effort.</b> A retry after a partial failure may create a second
 *       remote object. Callers may pass an {@code artifact.metadata()} key the destination can use
 *       to de-duplicate, but nothing in the SPI requires it.</li>
 * </ul>
 */
public interface ExportDestination {

    /**
     * Stable, machine-keyed identifier, e.g. {@code GOOGLE_DRIVE}. Upper snake case; used in job
     * payloads and the admin API, so it must not change once shipped.
     */
    String id();

    /** Human label for an admin UI, e.g. {@code "Google Drive"}. */
    String displayName();

    /**
     * The config fields this destination needs from an administrator, in display order. Drives the
     * generic admin form and is what a third-party destination publishes so Saiku can render a
     * config UI for it without knowing anything about the destination.
     */
    java.util.List<ExportDestinationConfigField> configFields();

    /**
     * Validate an admin-submitted config <b>before</b> it is stored. Throws
     * {@link ExportDeliveryException} with a sanitized message on anything the destination cannot
     * work with (a missing service-account path, a malformed folder id, …).
     *
     * <p>Must be cheap, side-effect-free and offline: it runs on the admin request thread and is
     * allowed to fail when the remote is unreachable, but should not <em>require</em> the remote.
     */
    void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException;

    /**
     * Deliver {@code artifact} using {@code config}. Blocks until the remote has acknowledged.
     *
     * @throws ExportDeliveryException on any failure, with a sanitized message (no secrets)
     */
    ExportDeliveryResult deliver(ExportArtifact artifact, ExportDestinationConfig config)
            throws ExportDeliveryException;
}
