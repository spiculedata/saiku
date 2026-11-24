/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.mcp;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of active MCP streamable-http sessions, keyed by the
 * {@code Mcp-Session-Id} header value. Singleton-scoped so it survives
 * across requests — the JSON-RPC {@code initialize} handshake mints a
 * session id, subsequent {@code tools/*} calls echo it back.
 *
 * <p>Lazy eviction on access: sessions whose last-touch is older than
 * {@link #idleTimeoutMs} get dropped when looked up. Suitable for the
 * single-node Saiku deployment; a horizontally-scaled setup would need
 * a shared store (Redis / JDBC).
 *
 * <p>Idle timeout is configurable via system property
 * {@code saiku.mcp.session.idleTimeoutSeconds} (default 3600). Issue #878.
 *
 * <p>saiku#2325: the store is bounded by {@code saiku.mcp.session.max} (default 1000). When
 * {@code create} finds it full it drops idle sessions, then the least-recently-active one; idle
 * sessions are also swept periodically from {@code touch} so they cannot accumulate unseen.
 *
 * <p>saiku#2542: every session is bound to the principal that created it. {@code touch} and
 * {@code drop} only ever act on a session whose stored principal matches the caller's — a
 * session id presented by a different user reads as unknown (null), so it can neither use nor
 * destroy another user's session.
 */
public final class McpSessionStore {

    /** Default idle timeout when {@code saiku.mcp.session.idleTimeoutSeconds} is unset. */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 60L * 60L * 1000L;

    private final ConcurrentMap<String, McpSession> sessions = new ConcurrentHashMap<>();
    /** Default cap on live sessions when {@code saiku.mcp.session.max} is unset. */
    private static final int DEFAULT_MAX_SESSIONS = 1000;

    private final long idleTimeoutMs;
    private final int maxSessions;
    private volatile long lastSweepMs = System.currentTimeMillis();

    public McpSessionStore() {
        this(parseTimeout(), parseMaxSessions());
    }

    McpSessionStore(long idleTimeoutMs) {
        this(idleTimeoutMs, DEFAULT_MAX_SESSIONS);
    }

    McpSessionStore(long idleTimeoutMs, int maxSessions) {
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxSessions = Math.max(1, maxSessions);
    }

    private static int parseMaxSessions() {
        String v = System.getProperty("saiku.mcp.session.max");
        if (v == null || v.isBlank()) return DEFAULT_MAX_SESSIONS;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : DEFAULT_MAX_SESSIONS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_SESSIONS;
        }
    }

    private static long parseTimeout() {
        String v = System.getProperty("saiku.mcp.session.idleTimeoutSeconds");
        if (v == null || v.isBlank()) return DEFAULT_IDLE_TIMEOUT_MS;
        try {
            return Long.parseLong(v.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return DEFAULT_IDLE_TIMEOUT_MS;
        }
    }

    /** Mint a new session owned by {@code principal} and return its id. Called from {@code initialize}. */
    public synchronized String create(String protocolVersion, String principal) {
        if (sessions.size() >= maxSessions) {
            sweepIdle(System.currentTimeMillis());
            while (sessions.size() >= maxSessions && evictLeastRecentlyActive()) {
                // keep evicting until there is room for the new session
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new McpSession(protocolVersion, principal, System.currentTimeMillis()));
        return id;
    }

    private void sweepIdle(long now) {
        lastSweepMs = now;
        sessions.entrySet().removeIf(e -> now - e.getValue().lastActivityMs() > idleTimeoutMs);
    }

    private boolean evictLeastRecentlyActive() {
        return sessions.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().lastActivityMs()))
                .map(e -> sessions.remove(e.getKey()) != null)
                .orElse(false);
    }

    /**
     * Look up a session by id on behalf of {@code principal}, touching its last-activity
     * timestamp. Returns null when the id is unknown, idle-expired (the stale entry is removed),
     * or owned by a different principal (saiku#2542 — a foreign principal reads the same "unknown
     * session" answer; the stored last-activity is deliberately not refreshed so probing a
     * foreign id cannot keep someone else's session alive).
     */
    public McpSession touch(String id, String principal) {
        if (id == null || id.isBlank()) return null;
        long now = System.currentTimeMillis();
        if (now - lastSweepMs > idleTimeoutMs / 2) sweepIdle(now);
        McpSession s = sessions.get(id);
        if (s == null) return null;
        if (now - s.lastActivityMs() > idleTimeoutMs) {
            sessions.remove(id);
            return null;
        }
        if (!samePrincipal(s.principal(), principal)) return null;
        sessions.put(id, new McpSession(s.protocolVersion(), s.principal(), now));
        return s;
    }

    /**
     * Drop a session explicitly — used by the JSON-RPC delete-session DELETE handler. Only the
     * session's owner may drop it (saiku#2542); a foreign or unknown id leaves the store
     * untouched and reports false.
     */
    public boolean drop(String id, String principal) {
        if (id == null || id.isBlank()) return false;
        McpSession s = sessions.get(id);
        if (s == null || !samePrincipal(s.principal(), principal)) return false;
        return sessions.remove(id, s);
    }

    private static boolean samePrincipal(String stored, String caller) {
        // Identity equality: an absent principal only ever matches another absent one — in
        // production the auth chain always supplies an authenticated name before this resource is
        // reached, and a test without a security context mints and uses its session the same way.
        return java.util.Objects.equals(stored, caller);
    }

    /** Test hook. */
    int size() {
        return sessions.size();
    }

    /** Test hook. */
    long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /** A live MCP session — immutable. Bound to the principal that created it (saiku#2542). */
    public record McpSession(String protocolVersion, String principal, long lastActivityMs) {}
}
