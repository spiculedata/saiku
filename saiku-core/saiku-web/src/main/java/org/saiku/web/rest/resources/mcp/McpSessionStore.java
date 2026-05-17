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
 */
public final class McpSessionStore {

    /** Default idle timeout when {@code saiku.mcp.session.idleTimeoutSeconds} is unset. */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 60L * 60L * 1000L;

    private final ConcurrentMap<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final long idleTimeoutMs;

    public McpSessionStore() {
        this(parseTimeout());
    }

    McpSessionStore(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
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
    public String create(String protocolVersion) {
        String id = UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new McpSession(protocolVersion, System.currentTimeMillis()));
        return id;
    }

    /**
     * Look up a session by id, touching its last-activity timestamp.
     * Returns null if unknown or idle-expired (and removes the stale entry).
     */
    public McpSession touch(String id) {
        if (id == null || id.isBlank()) return null;
        McpSession s = sessions.get(id);
        if (s == null) return null;
        long now = System.currentTimeMillis();
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
