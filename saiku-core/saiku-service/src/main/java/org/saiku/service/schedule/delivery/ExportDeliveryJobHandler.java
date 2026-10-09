/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDeliveryResult;
import org.saiku.service.export.destination.ExportDestination;
import org.saiku.service.export.destination.ExportDestinationConfig;
import org.saiku.service.export.destination.ExportDestinationConfigStore;
import org.saiku.service.export.destination.ExportDestinationRegistry;
import org.saiku.service.schedule.JobHandler;
import org.saiku.service.schedule.ScheduledJobFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code EXPORT_DELIVERY} {@link JobHandler} (saiku#1987) — the MVP job path that makes the export
 * destination SPI useful: on a schedule, produce an artifact from a saved query and deliver it to a
 * configured destination.
 *
 * <pre>{@code
 * {
 *   "type": "EXPORT_DELIVERY",
 *   "payload": {
 *     "destination": "GOOGLE_DRIVE",
 *     "source": { "type": "SAVED_QUERY_CSV", "savedQuery": "/sales/q1.sai" }
 *   }
 * }
 * }</pre>
 *
 * <p>Two halfs, kept deliberately separate:
 *
 * <ol>
 *   <li><b>Produce</b> — an {@link ExportArtifactProducer} (the "what"), currently the saved-query CSV
 *       producer. The producer runs the query under the owner-identity {@code SecurityContext} the
 *       scheduler already established, so RLS is the owner's, and it holds no session-scoped bean.</li>
 *   <li><b>Deliver</b> — the {@link ExportDestination} named in the payload (the "where"). Its config,
 *       including every credential, is resolved here from {@link ExportDestinationConfigStore} at
 *       delivery time.</li>
 * </ol>
 *
 * <p><b>The job payload carries no secrets, by construction.</b> It names a destination id; the store
 * resolves the credentials. A job file in {@code saiku-home/jobs/} is therefore safe to share, diff and
 * back up — which is the property that makes an operator-trusted {@code plugins/*.jar} destination (the
 * follow-up work) safe too.
 *
 * <p>It runs as the job owner (the {@code OwnerIdentityJobRunner} has already established the owner's
 * {@code SecurityContext}); this handler does <b>not</b> re-impersonate. Failures throw
 * {@link ExportDeliveryException} with a sanitized message — the engine records it, counts the failure
 * and applies backoff. No exception message on this path may contain a token, a private key or a
 * remote response body.
 */
public final class ExportDeliveryJobHandler implements JobHandler {

    public static final String JOB_TYPE = "EXPORT_DELIVERY";

    private static final Logger log = LoggerFactory.getLogger(ExportDeliveryJobHandler.class);

    private final ExportDestinationRegistry registry;
    private final ExportDestinationConfigStore configStore;
    private final Map<String, ExportArtifactProducer> producers;

    public ExportDeliveryJobHandler(
            ExportDestinationRegistry registry,
            ExportDestinationConfigStore configStore,
            Map<String, ExportArtifactProducer> producers) {
        if (registry == null || configStore == null || producers == null) {
            throw new IllegalArgumentException("registry, configStore and producers are required");
        }
        this.registry = registry;
        this.configStore = configStore;
        this.producers = Map.copyOf(producers);
    }

    @Override
    public void handle(ScheduledJobFile job) throws Exception {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        Map<String, Object> payload = job.getPayload();

        // (1) Resolve the destination by id. An unknown id throws with the list of registered ids,
        //     which is the single most useful thing an operator can be told here.
        String destinationId = readString(payload, "destination");
        if (StringUtils.isBlank(destinationId)) {
            throw new IllegalArgumentException("payload.destination is required (e.g. GOOGLE_DRIVE)");
        }
        ExportDestination destination = registry.require(destinationId.trim().toUpperCase(Locale.ROOT));

        // (2) Parse the source block and find its producer. Producer keys are matched case-insensitively
        //     so a hand-edited job file isn't rejected on cosmetics.
        ExportSourceSpec spec = ExportSourceSpec.fromPayload(payload == null ? null : payload.get("source"));
        ExportArtifactProducer producer = findProducer(spec.type());
        if (producer == null) {
            throw new IllegalArgumentException("no artifact producer registered for source type " + spec.type()
                    + " (registered: " + String.join(", ", producers.keySet()) + ")");
        }

        // (3) Resolve the destination's config — INCLUDING its credentials — from the store. The job
        //     payload never carried them.
        ExportDestinationConfig config = configStore.get(destination.id());
        if (config.isEmpty()) {
            throw ExportDeliveryException.misconfigured("destination " + destination.id()
                    + " has no saved configuration (set it via POST /saiku/admin/export-destinations/"
                    + destination.id() + ")");
        }
        // Re-validate at delivery time, not just at admin-save time: the key file can have been moved,
        // chmod'ed away or deleted since the config was written.
        destination.validateConfig(config);

        // (4) Produce, then deliver.
        ExportArtifact artifact = producer.produce(spec);
        ExportDeliveryResult result = destination.deliver(artifact, config);
        log.info(
                "Export delivery job {}: delivered {} to {} ({})",
                job.getId(),
                artifact.filename(),
                destination.id(),
                result.description());
    }

    private ExportArtifactProducer findProducer(String type) {
        if (type == null) {
            return null;
        }
        ExportArtifactProducer exact = producers.get(type);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ExportArtifactProducer> e : producers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(type)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String readString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object v = payload.get(key);
        return v instanceof String s ? s : null;
    }
}
