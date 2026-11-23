/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import java.util.Objects;
import java.util.Set;

/**
 * An admin-registered outbound MCP server (saiku#1425): a remote streamable-http MCP endpoint that
 * DimSum may call mid-conversation for context (Notion, Linear, Sentry, a custom internal tool, …).
 *
 * <p>Persisted as JSON under {@code saiku-home/mcp-servers/*.json} — same on-disk model as {@link
 * org.saiku.service.olap.ai.ask.AgentSpace} / {@code AgentSkill}. See {@link McpOutboundServerParser}
 * for the file shape and {@link McpOutboundServerRegistry} for the scan/CRUD machinery.
 *
 * <p><strong>Default-off per-tool enablement</strong> (matching Cube's MCP Connectors shape, per the
 * saiku#1425 discussion): {@code enabledTools} is an explicit allowlist. A freshly registered server
 * with an empty {@code enabledTools} exposes NOTHING to the LLM — an admin must discover the
 * server's tool catalogue and opt each tool in individually. This is the opposite default from
 * {@link org.saiku.service.olap.ai.ask.AgentSpace#skillAllowlist()} (empty = allow all) precisely
 * because an outbound tool can reach a third-party system; widening the agent's reach must be an
 * explicit admin act, never a byproduct of "just registering the server".
 *
 * <p><strong>Credentials never leave this record.</strong> {@code authHeaderValue} is decrypted at
 * parse time ({@link McpOutboundServerParser}) and used only inside {@link McpOutboundClient} to set
 * one outbound HTTP header; it is never echoed back into an admin API response (see the DTO layer in
 * {@code McpOutboundAdminResource}), never logged, and never influenced by tool-call arguments — the
 * argument JSON the model emits can only populate the JSON-RPC {@code params}, never a header.
 */
public record McpOutboundServer(
        String id,
        String name,
        String url,
        String authHeaderName,
        String authHeaderValue,
        Set<String> enabledTools,
        String sourcePath) {

    public McpOutboundServer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (id.isBlank()) {
            throw new IllegalArgumentException("server id must be non-blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("server name must be non-blank");
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("server url must be non-blank");
        }
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
    }

    /** True when the server carries a header credential to send with every outbound call. */
    public boolean hasAuthHeader() {
        return authHeaderName != null && !authHeaderName.isBlank() && authHeaderValue != null;
    }

    /** True when {@code toolName} is in the explicit enablement allowlist. Empty allowlist = none enabled. */
    public boolean isToolEnabled(String toolName) {
        return toolName != null && enabledTools.contains(toolName);
    }
}
