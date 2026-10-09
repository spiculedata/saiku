/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import java.util.Objects;

/**
 * One outbound MCP tool, enabled by an admin and flattened into the ask-layer catalogue
 * (saiku#1425).
 *
 * @param qualifiedName the name advertised to the LLM as a callable function/tool, e.g. {@code
 *     mcp__notion__search_docs} — see {@link McpOutboundToolCatalog#qualify}. Sanitised to the
 *     charset every provider's function-name field accepts.
 * @param serverId the owning {@link McpOutboundServer#id()}.
 * @param toolName the tool's real name as advertised by the remote server's {@code tools/list}
 *     (used verbatim in the {@code tools/call} dispatch — only {@link #qualifiedName} is sanitised).
 * @param description the remote server's tool description, shown to the LLM verbatim.
 * @param inputSchemaJson the remote server's JSON-Schema for the tool's arguments.
 */
public record McpOutboundToolDescriptor(
        String qualifiedName, String serverId, String toolName, String description, String inputSchemaJson) {

    public McpOutboundToolDescriptor {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(toolName, "toolName");
        description = description == null ? "" : description;
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
    }
}
