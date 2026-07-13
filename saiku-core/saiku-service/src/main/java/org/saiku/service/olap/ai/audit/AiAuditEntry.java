/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One audit record for a single AI API call (saiku#906). Captures the
 * <em>shape</em> of the call — who, which endpoint, the policy decision, a
 * fingerprint of the schema sent, token counts, latency, outcome — but never
 * the prompt content or any cell values. Logging the content would make the
 * audit trail a second PII surface; the shape is what an auditor needs to
 * answer "what did the AI feature send, and was it allowed?".
 *
 * <p>Public fields + no-arg constructor so Jackson can round-trip it to/from the
 * JSONL audit file. {@code NON_NULL} keeps unset fields (e.g. {@code model} on a
 * non-LLM endpoint) out of the serialised line.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAuditEntry {

    /** Outcome values — kept as constants so writers + the read API agree. */
    public static final String OUTCOME_SUCCESS = "success";

    public static final String OUTCOME_VALIDATION_ERROR = "validation_error";
    public static final String OUTCOME_DENIED = "denied";
    public static final String OUTCOME_UPSTREAM_ERROR = "upstream_error";
    public static final String OUTCOME_ERROR = "error";

    public static final String DECISION_ALLOW = "allow";
    public static final String DECISION_DENY = "deny";

    /** ISO-8601 UTC timestamp, second precision (e.g. {@code 2026-06-15T10:23:14Z}). */
    public String ts;
    /** Tenant id when running multi-tenant; null on single-tenant OSS. */
    public String tenant;
    /** Authenticated principal name, or {@code anonymous}. For an embed-RLS
     *  call this is the run-as owner; the end-user identity is {@link #sub}. */
    public String user;
    /** End-user identity from an embed JWT's {@code sub} claim (saiku#1104),
     *  so the trail shows WHO the embedder asserted, not just the run-as owner.
     *  Null for non-embed-JWT calls. */
    public String sub;
    /** REST path hit, e.g. {@code /saiku/api/ai/query}. */
    public String endpoint;
    /** Active data-exposure policy tier (#903): schema-only | aggregated | full. */
    public String policy;
    /** {@code allow} or {@code deny} — whether the policy permitted this call. */
    public String policyDecision;
    /** Upstream LLM provider for NL-ask calls (#904): anthropic | openai | ollama | noop. Null for non-LLM endpoints. */
    public String upstream;
    /** Upstream model id when known. Null for non-LLM endpoints. */
    public String model;
    /** {@code sha256:<hex>} over the sorted names of the dims/hiers/levels/measures
     *  sent — lets an auditor confirm what shape of metadata crossed without
     *  storing the metadata itself. */
    public String schemaFingerprint;
    /** Cube the call targeted (canonical {@code connection/catalog/schema/cube} or its display name). */
    public String cube;
    /** Approx. prompt token count for LLM endpoints. Null when not applicable. */
    public Integer promptTokens;
    /** Approx. response token count for LLM endpoints. Null when not applicable. */
    public Integer responseTokens;
    /** Server-side wall-clock latency for the call, in milliseconds. */
    public Long latencyMs;
    /** One of the {@code OUTCOME_*} constants. */
    public String outcome;
    /** Error class / short reason on a non-success outcome — never the full
     *  message (could leak SQL / paths / values). Null on success. */
    public String error;

    public AiAuditEntry() {}
}
