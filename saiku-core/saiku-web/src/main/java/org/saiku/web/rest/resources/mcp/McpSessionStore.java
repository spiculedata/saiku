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

    /** Mint a new session and return its id. Called from {@code initialize}. */
    public synchronized String create(String protocolVersion) {
        if (sessions.size() >= maxSessions) {
            sweepIdle(System.currentTimeMillis());
            while (sessions.size() >= maxSessions && evictLeastRecentlyActive()) {
                // keep evicting until there is room for the new session
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new McpSession(protocolVersion, System.currentTimeMillis()));
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
     * Look up a session by id, touching its last-activity timestamp.
     * Returns null if unknown or idle-expired (and removes the stale entry).
     */
    public McpSession touch(String id) {
        if (id == null || id.isBlank()) return null;
        long now = System.currentTimeMillis();
        if (now - lastSweepMs > idleTimeoutMs / 2) sweepIdle(now);
        McpSession s = sessions.get(id);
        if (s == null) return null;
        if (now - s.lastActivityMs() > idleTimeoutMs) {
            sessions.remove(id);
            return null;
        }
        sessions.put(id, new McpSession(s.protocolVersion(), now));
        return s;
    }

    /** Drop a session explicitly — used by the JSON-RPC delete-session DELETE handler. */
    public boolean drop(String id) {
        return id != null && sessions.remove(id) != null;
    }

    /** Test hook. */
    int size() {
        return sessions.size();
    }

    /** Test hook. */
    long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /** A live MCP session — immutable. */
    public record McpSession(String protocolVersion, long lastActivityMs) {}
}
