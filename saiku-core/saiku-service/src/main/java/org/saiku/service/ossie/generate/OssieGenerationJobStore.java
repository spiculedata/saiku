/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link OssieGenerationJob} registry keyed by UUID with sliding-TTL expiry.
 *
 * <p>Same contract as {@link org.saiku.service.schema.generate.session.SchemaGenSessionStore}, kept
 * as a separate type rather than shared because the two hold different state (a generation job has
 * no user-editable draft — it's a one-shot run to a pair of files). Sharing one store would mean
 * either leaking draft trees into the Ossie surface or widening the schemagen session shape for
 * every existing caller.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}. Spawns no eviction thread; callers invoke {@link
 * #evictExpired()} periodically. Default TTL 30 minutes.
 */
public class OssieGenerationJobStore {

    /** Default TTL — 30 minutes of idle before eviction. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, OssieGenerationJob> jobs = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public OssieGenerationJobStore() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public OssieGenerationJobStore(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public OssieGenerationJobStore(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * Create a fresh job under a new random UUID and publish it. The job is inserted at {@link
     * OssieGenerationJob.Stage#PENDING} so an immediate poll is a hit, not a miss.
     */
    public OssieGenerationJob create(String dataSourceId, String modelName) {
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        String name = (modelName == null || modelName.isBlank()) ? dataSourceId : modelName;
        OssieGenerationJob job = new OssieGenerationJob(UUID.randomUUID().toString(), dataSourceId, name, clock);
        jobs.put(job.id(), job);
        return job;
    }

    /** Fetch a job if present and unexpired; touches {@code lastAccessedAt} on success. */
    public Optional<OssieGenerationJob> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        OssieGenerationJob job = jobs.get(id);
        if (job == null) {
            return Optional.empty();
        }
        if (isExpired(job, clock.instant())) {
            jobs.remove(id, job);
            return Optional.empty();
        }
        job.touch();
        return Optional.of(job);
    }

    public void remove(String id) {
        if (id != null) {
            jobs.remove(id);
        }
    }

    /** Drop every job idle beyond the TTL. */
    public void evictExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, OssieGenerationJob>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, OssieGenerationJob> e = it.next();
            if (isExpired(e.getValue(), now)) {
                it.remove();
            }
        }
    }

    /** Visible for tests / metrics. */
    public int size() {
        return jobs.size();
    }

    private boolean isExpired(OssieGenerationJob job, Instant now) {
        return Duration.between(job.lastAccessedAt(), now).compareTo(ttl) > 0;
    }
}
