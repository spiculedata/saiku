/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates every admin-registered {@link McpOutboundServer}'s ENABLED tools into one flat
 * catalogue the ask layer can hand to an LLM as additional callable functions (saiku#1425) —
 * "the LLM sees Saiku tools + outbound tools in one flat catalogue with server-name prefix", per
 * the issue's acceptance criteria.
 *
 * <p>Discovery failures are isolated per server: an unreachable server simply contributes no tools
 * this refresh — its tools are ABSENT from the catalogue, never a hard error that would take down
 * the whole ask flow. Refreshed lazily, cached for {@link #cacheTtl} so a chained-ask loop's several
 * turns don't each pay for a fresh round-trip to every registered server; a config change (server
 * added/edited/removed) is picked up immediately because the cache also keys off the registry's own
 * signature-based rescan.
 */
public final class McpOutboundToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpOutboundToolCatalog.class);
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final McpOutboundServerRegistry registry;
    private final McpOutboundClient client;
    private final Duration cacheTtl;
    private final java.util.function.LongSupplier nanoClock;

    private final AtomicReference<Cache> cache = new AtomicReference<>();

    public McpOutboundToolCatalog(McpOutboundServerRegistry registry, McpOutboundClient client) {
        this(registry, client, DEFAULT_TTL, System::nanoTime);
    }

    /** Package-visible ctor for tests — injects a fake clock so TTL expiry is deterministic. */
    McpOutboundToolCatalog(
            McpOutboundServerRegistry registry,
            McpOutboundClient client,
            Duration cacheTtl,
            java.util.function.LongSupplier nanoClock) {
        this.registry = registry;
        this.client = client;
        this.cacheTtl = cacheTtl == null ? DEFAULT_TTL : cacheTtl;
        this.nanoClock = nanoClock;
    }

    /**
     * Flattened, enabled-only tool catalogue across every registered server. Servers with an empty
     * {@code enabledTools} allowlist (the default — see {@link McpOutboundServer}) contribute
     * nothing here even though {@code tools/list} may have discovered more.
     */
    public List<McpOutboundToolDescriptor> tools() {
        return refresh().descriptors;
    }

    /** True when at least one server is registered AND has at least one tool enabled. */
    public boolean hasEnabledTools() {
        return !tools().isEmpty();
    }

    /** Force a rescan of every registered server on the next {@link #tools()} call. */
    public void forceRefresh() {
        cache.set(null);
    }

    /**
     * Dispatch a call the LLM made against {@code qualifiedName} (as advertised by {@link #tools()}).
     * Re-validates the tool is STILL enabled at call time (defence in depth — a provider response is
     * untrusted input; a model could in principle echo a stale or fabricated qualified name) rather
     * than trusting the name blindly.
     */
    public McpOutboundClient.CallResult callTool(String qualifiedName, String argumentsJson) {
        Cache c = refresh();
        McpOutboundToolDescriptor descriptor = c.byQualifiedName.get(qualifiedName);
        if (descriptor == null) {
            return McpOutboundClient.CallResult.failure("unknown or disabled tool: " + qualifiedName);
        }
        return registry.get(descriptor.serverId())
                .map(server -> {
                    if (!server.isToolEnabled(descriptor.toolName())) {
                        return McpOutboundClient.CallResult.failure("tool is no longer enabled: " + qualifiedName);
                    }
                    return client.callTool(server, descriptor.toolName(), argumentsJson);
                })
                .orElseGet(() ->
                        McpOutboundClient.CallResult.failure("server no longer registered: " + descriptor.serverId()));
    }

    /* ------------------------------------ caching ------------------------------------ */

    private Cache refresh() {
        Cache current = cache.get();
        long regSig = registrySignature();
        if (current != null && current.registrySignature == regSig && !expired(current)) {
            return current;
        }
        Cache next = build(regSig);
        cache.set(next);
        return next;
    }

    private boolean expired(Cache c) {
        return nanoClock.getAsLong() - c.builtAtNanos >= cacheTtl.toNanos();
    }

    /** Cheap identity for "did the registered-server set change" — list size + id set is enough. */
    private long registrySignature() {
        List<McpOutboundServer> servers = registry.list();
        long sig = 1125899906842597L;
        sig = sig * 31 + servers.size();
        for (McpOutboundServer s : servers) {
            sig = sig * 31 + s.id().hashCode();
            sig = sig * 31 + s.url().hashCode();
            sig = sig * 31 + s.enabledTools().hashCode();
        }
        return sig;
    }

    private Cache build(long regSig) {
        List<McpOutboundToolDescriptor> descriptors = new ArrayList<>();
        Map<String, McpOutboundToolDescriptor> byQualifiedName = new HashMap<>();
        Set<String> usedNames = new HashSet<>();

        for (McpOutboundServer server : registry.list()) {
            if (server.enabledTools().isEmpty()) {
                continue; // default-off — nothing to discover-and-advertise for this server
            }
            McpOutboundClient.DiscoveryResult result = client.discoverTools(server);
            if (!result.ok()) {
                log.info("outbound MCP catalogue: skipping server '{}' this refresh ({})", server.id(), result.error());
                continue;
            }
            for (McpOutboundClient.RemoteTool remote : result.tools()) {
                if (!server.isToolEnabled(remote.name())) {
                    continue; // discovered but not admin-enabled
                }
                String qualified = qualify(server.id(), remote.name(), usedNames);
                McpOutboundToolDescriptor descriptor = new McpOutboundToolDescriptor(
                        qualified, server.id(), remote.name(), remote.description(), remote.inputSchemaJson());
                descriptors.add(descriptor);
                byQualifiedName.put(qualified, descriptor);
            }
        }
        return new Cache(regSig, nanoClock.getAsLong(), List.copyOf(descriptors), Map.copyOf(byQualifiedName));
    }

    /** Max length OpenAI's {@code function.name} field accepts; Anthropic's tool name limit is more generous. */
    private static final int MAX_QUALIFIED_NAME_LEN = 64;

    /**
     * Builds the LLM-facing tool name: {@code mcp__<serverId>__<toolName>}, sanitised to
     * {@code [a-zA-Z0-9_-]} (the charset every provider's function-name field accepts — remote tool
     * names are untrusted and may contain anything), truncated to {@value #MAX_QUALIFIED_NAME_LEN}
     * chars, and de-duplicated with a numeric suffix on collision (two servers exposing
     * same-named/near-colliding tools after sanitisation).
     */
    static String qualify(String serverId, String toolName, Set<String> usedNames) {
        String raw = "mcp__" + serverId + "__" + toolName;
        String sanitised = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitised.length() > MAX_QUALIFIED_NAME_LEN) {
            sanitised = sanitised.substring(0, MAX_QUALIFIED_NAME_LEN);
        }
        String candidate = sanitised;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            String suffixStr = "_" + suffix;
            int keep = Math.min(sanitised.length(), MAX_QUALIFIED_NAME_LEN - suffixStr.length());
            candidate = sanitised.substring(0, Math.max(0, keep)) + suffixStr;
            suffix++;
        }
        return candidate;
    }

    /** True when {@code qualifiedName} looks like an outbound-MCP tool name (saiku#1425 prefix). */
    public static boolean isMcpQualifiedName(String qualifiedName) {
        return qualifiedName != null && qualifiedName.startsWith("mcp__");
    }

    private record Cache(
            long registrySignature,
            long builtAtNanos,
            List<McpOutboundToolDescriptor> descriptors,
            Map<String, McpOutboundToolDescriptor> byQualifiedName) {}
}
