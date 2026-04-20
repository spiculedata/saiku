package org.saiku.service.schema.generate.session;

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
 * In-memory {@link SchemaGenSession} registry keyed by UUID with sliding-TTL expiry.
 *
 * <p>Thread-safe via a {@link ConcurrentHashMap}. Does <strong>not</strong> spawn any eviction
 * threads — callers are expected to invoke {@link #evictExpired()} periodically (e.g. from a
 * scheduled task) to reclaim memory from abandoned sessions.
 *
 * <p>TTL semantics: a session is considered expired when
 * {@code now() - session.lastAccessedAt() > ttl}. Every successful {@link #get(String)} touches
 * the session, so active workflows stay alive indefinitely.
 *
 * <p>Default TTL is 30 minutes.
 */
public class SchemaGenSessionStore {

    /** Default session TTL — 30 minutes of idle before eviction. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, SchemaGenSession> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    /** Convenience ctor: 30-minute TTL, system UTC clock. */
    public SchemaGenSessionStore() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public SchemaGenSessionStore(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public SchemaGenSessionStore(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Duration ttl() {
        return ttl;
    }

    /** Create a fresh session for {@code dataSourceId}, stored under a new random UUID. */
    public SchemaGenSession create(String dataSourceId) {
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        String id = UUID.randomUUID().toString();
        SchemaGenSession session = new SchemaGenSession(id, dataSourceId, clock);
        sessions.put(id, session);
        return session;
    }

    /**
     * Fetch a session if it exists and is not expired. Touches {@code lastAccessedAt} on success,
     * implementing sliding-TTL semantics.
     */
    public Optional<SchemaGenSession> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        SchemaGenSession s = sessions.get(id);
        if (s == null) {
            return Optional.empty();
        }
        if (isExpired(s, clock.instant())) {
            sessions.remove(id, s);
            return Optional.empty();
        }
        s.touch();
        return Optional.of(s);
    }

    public void remove(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /** Scan the store and drop every session whose idle time has exceeded the TTL. */
    public void evictExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, SchemaGenSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SchemaGenSession> e = it.next();
            if (isExpired(e.getValue(), now)) {
                it.remove();
            }
        }
    }

    /** Visible for tests/metrics. */
    public int size() {
        return sessions.size();
    }

    private boolean isExpired(SchemaGenSession s, Instant now) {
        return Duration.between(s.lastAccessedAt(), now).compareTo(ttl) > 0;
    }
}
