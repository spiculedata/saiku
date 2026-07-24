/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiRequestJsonSchema;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.KAnonymityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator for the natural-language ask layer.
 *
 * <p>Composes three pieces:
 *
 * <ol>
 *   <li>Loads the {@link AiSchema} for the requested cube via the existing
 *       {@link AiCubeMetadataService} (same path as {@code /ai/schema}). The schema is the only
 *       grounding the LLM gets — there's nothing else it can use to invent names.
 *   <li>Calls the configured {@link NlAskProvider} with the schema, the AiQueryRequest JSON Schema,
 *       and the user's question + history.
 *   <li>Deserialises the model's JSON output into an {@link AiQueryRequest} so the caller can route
 *       it through the existing {@code /ai/query} converter unchanged.
 * </ol>
 *
 * <p>Execution of the resulting {@link AiQueryRequest} is the resource's concern; this service
 * stops at producing a typed request the user could have authored by hand.
 */
public class AiAskService {

    private static final Logger log = LoggerFactory.getLogger(AiAskService.class);

    private final AiCubeMetadataService metadataService;
    private final NlAskProvider provider;
    private final ObjectMapper mapper;

    /**
     * Skill catalogue used by this service. Injected by Spring via {@link #setSkills} — kept as a
     * mutable field (not a ctor arg) so existing wiring (two-arg ctor) can stay unchanged, and so
     * tests can install a null registry to exercise the un-skilled code path.
     */
    private AgentSkillRegistry skills;

    /**
     * Agent-space catalogue (saiku#1440). Same wiring model as skills — optional, injected via
     * setter so the classic ask path stays working when spaces aren't configured.
     */
    private AgentSpaceRegistry spaces;

    /**
     * Dedicated LLM-egress guard (Option A). Answers "may cell data leave the box to a third-party
     * LLM vendor?" — resolved from {@code SAIKU_AI_LLM_EGRESS} / {@code ai.llm.egress}, SEPARATE
     * from the data-return {@link AiPolicyGuard} on {@code SAIKU_AI_POLICY}. Injected via setter so
     * existing two-arg construction stays unchanged. FAIL-CLOSED: a null (unwired) guard is treated
     * as "egress not permitted", so an unconfigured instance strips cell data from the prompt rather
     * than leaking it.
     */
    private AiPolicyGuard egressGuard;

    /** Pure schema→ThinQuery converter (stateless), same instance-per-service pattern as AiQueryResource. */
    private final AiSchemaConverter converter = new AiSchemaConverter();

    /**
     * Executes a converted {@link org.saiku.olap.query2.ThinQuery} to a {@code CellDataSet}.
     * Setter-injected (wired to {@code thinQueryBean} in saiku-beans.xml) so existing two-arg
     * construction and every unit test that builds this service without an executor stay
     * unchanged. Null when unwired — the chained-ask loop (a later task) treats a null executor
     * as "server-side execution unavailable".
     */
    private ThinQueryService thinQueryService;

    /**
     * K-anonymity small-cell suppression applied to the executed {@code CellDataSet} before it is
     * digested and fed back to the model — parity with the masking every other egress path (records
     * / matrix format via {@code AiQueryResource}) already applies. Setter-injected (wired to
     * {@code kAnonymityFilter} in saiku-beans.xml), same optional-field pattern as {@link
     * #egressGuard} / {@link #thinQueryService}. Null-tolerant: an unwired filter means no masking
     * is applied here, but that's safe rather than a leak — every OTHER egress path still applies
     * its own {@code kAnonymityFilter} bean, and the loop is fail-closed on egress itself (schema-only
     * stops before any cell data is even executed/digested).
     */
    private KAnonymityFilter kAnonymityFilter;

    public AiAskService(AiCubeMetadataService metadataService, NlAskProvider provider) {
        this(metadataService, provider, defaultMapper());
    }

    public AiAskService(AiCubeMetadataService metadataService, NlAskProvider provider, ObjectMapper mapper) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Spring setter — wired to {@code skillRegistryBean} in {@code saiku-beans.xml}. */
    public void setSkills(AgentSkillRegistry skills) {
        this.skills = skills;
    }

    /**
     * Skill catalogue used by this service, if wired. May be {@code null} on legacy setups without
     * skills configured — the REST resource guards on that before delegating.
     */
    public AgentSkillRegistry skills() {
        return skills;
    }

    /** Spring setter — wired to {@code agentSpaceRegistryBean} in {@code saiku-beans.xml}. */
    public void setSpaces(AgentSpaceRegistry spaces) {
        this.spaces = spaces;
    }

    /** Agent-space catalogue, or {@code null} if the operator hasn't configured one. */
    public AgentSpaceRegistry spaces() {
        return spaces;
    }

    /** Spring setter — wired to {@code aiLlmEgressGuard} in {@code saiku-beans.xml}. */
    public void setEgressGuard(AiPolicyGuard egressGuard) {
        this.egressGuard = egressGuard;
    }

    /** The dedicated LLM-egress guard, or {@code null} if not wired (treated as egress-denied). */
    public AiPolicyGuard egressGuard() {
        return egressGuard;
    }

    /** Spring setter — wired to {@code thinQueryBean} in {@code saiku-beans.xml}. */
    public void setThinQueryService(ThinQueryService thinQueryService) {
        this.thinQueryService = thinQueryService;
    }

    /** The query executor, or {@code null} if not wired. */
    public ThinQueryService thinQueryService() {
        return thinQueryService;
    }

    /** Spring setter — wired to {@code kAnonymityFilter} in {@code saiku-beans.xml}. */
    public void setKAnonymityFilter(KAnonymityFilter kAnonymityFilter) {
        this.kAnonymityFilter = kAnonymityFilter;
    }

    /** The k-anonymity filter, or {@code null} if not wired (no grid masking applied). */
    public KAnonymityFilter kAnonymityFilter() {
        return kAnonymityFilter;
    }

    /** The stateless schema→ThinQuery converter. */
    public AiSchemaConverter converter() {
        return converter;
    }

    /**
     * Whether cell data (the cellset digest) may egress to the LLM vendor under the active egress
     * posture. FAIL-CLOSED: a null guard, or a guard that doesn't permit {@link
     * AiDataKind#AGGREGATED_RESULT_VALUES}, returns {@code false} — the digest is stripped and the
     * prompt is schema-only.
     */
    private boolean egressPermitsCellData() {
        return egressGuard != null && egressGuard.canSend(AiDataKind.AGGREGATED_RESULT_VALUES);
    }

    /**
     * Whether the underlying provider can answer a real request. Surfaced by the
     * {@code /ai/ask/health} endpoint so the UI can hide the "Ask the AI" button on instances that
     * haven't wired up an LLM key.
     */
    public boolean isConfigured() {
        return provider.isConfigured();
    }

    /**
     * Result of an {@link #ask(AiCubeRef, String, List)} call.
     *
     * <p>Exactly one of {@code request} / {@code insight} / {@code viewChange} / {@code
     * emailDraft} is non-null on success (matched to {@link #kind()}); all are null on degraded.
     */
    public record AskOutcome(
            Kind kind,
            boolean degraded,
            String reason,
            AiQueryRequest request,
            AiInsight insight,
            AiViewChange viewChange,
            AiEmailDraft emailDraft,
            String model,
            SpaceAccess denial) {

        public enum Kind {
            QUERY,
            INSIGHT,
            VIEW_CHANGE,
            EMAIL_DRAFT
        }

        public static AskOutcome ok(AiQueryRequest request, String model) {
            return new AskOutcome(Kind.QUERY, false, null, request, null, null, null, model, SpaceAccess.OK);
        }

        public static AskOutcome okInsight(AiInsight insight, String model) {
            return new AskOutcome(Kind.INSIGHT, false, null, null, insight, null, null, model, SpaceAccess.OK);
        }

        public static AskOutcome okViewChange(AiViewChange viewChange, String model) {
            return new AskOutcome(Kind.VIEW_CHANGE, false, null, null, null, viewChange, null, model, SpaceAccess.OK);
        }

        public static AskOutcome okEmailDraft(AiEmailDraft emailDraft, String model) {
            return new AskOutcome(Kind.EMAIL_DRAFT, false, null, null, null, null, emailDraft, model, SpaceAccess.OK);
        }

        /** Provider-side degrade (transport/parse/refusal) — carries no space-scope denial. */
        public static AskOutcome degraded(String reason, String model) {
            return new AskOutcome(null, true, reason, null, null, null, null, model, SpaceAccess.OK);
        }

        /**
         * Space-scope denial (saiku#1465). The {@code denial} code lets the web layer map to a real
         * HTTP status without prose-prefix matching on {@link #reason()}.
         */
        public static AskOutcome degraded(String reason, String model, SpaceAccess denial) {
            return new AskOutcome(null, true, reason, null, null, null, null, model, denial);
        }
    }

    /**
     * Ordered transcript of one chained ask: each step reuses the single-turn {@link AskOutcome}
     * (QUERY for a built+executed query, INSIGHT/VIEW_CHANGE/EMAIL_DRAFT for a terminal, or a
     * degraded step for an error / policy stop). {@code hitStepLimit} is true when the cap was
     * reached while the model was still emitting queries (no report produced).
     */
    public record AskChain(List<AskOutcome> steps, boolean hitStepLimit) {}

    /**
     * Translate a natural-language question against the cube pointed to by {@code ref}.
     *
     * @param ref cube to target; must be non-null and have a non-null cubeName
     * @param question free-form English question; must be non-blank
     * @param history prior turns; may be null / empty for single-shot asks
     */
    public AskOutcome ask(AiCubeRef ref, String question, List<NlAskMessage> history) {
        return ask(ref, question, history, null, NlAskRequest.ForceTool.AUTO, null);
    }

    public AskOutcome ask(AiCubeRef ref, String question, List<NlAskMessage> history, String cellsetDigest) {
        return ask(ref, question, history, cellsetDigest, NlAskRequest.ForceTool.AUTO, null);
    }

    public AskOutcome ask(
            AiCubeRef ref,
            String question,
            List<NlAskMessage> history,
            String cellsetDigest,
            NlAskRequest.ForceTool forceTool) {
        return ask(ref, question, history, cellsetDigest, forceTool, null);
    }

    /**
     * Same as {@link #ask(AiCubeRef, String, List)} but accepts the user's currently-rendered
     * cellset as a markdown digest AND a tool-choice override. The digest lets the model pick
     * {@link AskOutcome.Kind#INSIGHT} (analyse the current data) or {@link
     * AskOutcome.Kind#VIEW_CHANGE} (pick the right chart) in addition to {@link
     * AskOutcome.Kind#QUERY}. When {@code cellsetDigest} is null/blank, the model has no data
     * context and can only build queries.
     *
     * <p>{@code forceTool} narrows the provider's tool list when the user explicitly picked an
     * intent in the drawer's mode picker. Default {@link NlAskRequest.ForceTool#AUTO} leaves all
     * four tools available so the LLM routes by question shape.
     */
    public AskOutcome ask(
            AiCubeRef ref,
            String question,
            List<NlAskMessage> history,
            String cellsetDigest,
            NlAskRequest.ForceTool forceTool,
            AiQueryRequest currentQuery) {
        return askInternal(ref, question, history, cellsetDigest, forceTool, currentQuery, null);
    }

    /**
     * Space-scoped ask (saiku#1440). The persona referenced by {@code spaceId} pins the cube
     * allowlist, filters the skill catalogue, and prepends its {@code systemPrompt} to the LLM
     * system message. Callers can't escape the persona by injecting a cube ref outside the
     * allowlist — the enforcement is server-side.
     *
     * <p>When {@code ref} is null, the space's {@link AgentSpace#defaultCube() default cube} is
     * used. When both are set, {@code ref} must be in the allowlist or the call returns
     * {@code degraded("FORBIDDEN: …")}. When the space doesn't exist, returns
     * {@code degraded("space not found: …")}.
     */
    public AskOutcome askInSpace(
            String spaceId,
            AiCubeRef ref,
            String question,
            List<NlAskMessage> history,
            String cellsetDigest,
            NlAskRequest.ForceTool forceTool,
            AiQueryRequest currentQuery) {
        if (spaces == null) {
            return AskOutcome.degraded(
                    "agent spaces are not configured on this instance", null, SpaceAccess.SPACES_NOT_CONFIGURED);
        }
        if (spaceId == null || spaceId.isBlank()) {
            return AskOutcome.degraded("space id required", null, SpaceAccess.SPACE_NOT_FOUND);
        }
        java.util.Optional<AgentSpace> maybe = spaces.get(spaceId);
        if (maybe.isEmpty()) {
            return AskOutcome.degraded("space not found: " + spaceId, null, SpaceAccess.SPACE_NOT_FOUND);
        }
        AgentSpace space = maybe.get();
        AiCubeRef effectiveRef = ref != null ? ref : space.defaultCube();
        if (effectiveRef == null) {
            return AskOutcome.degraded("space has no cubes in its allowlist", null, SpaceAccess.FORBIDDEN);
        }
        if (!space.allowsCube(effectiveRef)) {
            // Persona guardrail — the caller tried to point the space at a cube outside its
            // allowlist. Deny loudly rather than silently rewrite to the default; a UI that hands
            // us a stale cube ref should get corrected, not silently reinterpreted.
            return AskOutcome.degraded(
                    "FORBIDDEN: cube " + effectiveRef.getCubeName() + " is not in space '" + spaceId + "' allowlist",
                    null,
                    SpaceAccess.FORBIDDEN);
        }
        return askInternal(effectiveRef, question, history, cellsetDigest, forceTool, currentQuery, space);
    }

    /**
     * Result of a synchronous space-access pre-flight — the input-side scope decision, made
     * without calling the LLM. Lets a streaming endpoint map a scope denial to a real HTTP status
     * (403 / 404 / 503) <em>before</em> it commits to a 200 event-stream, instead of burying the
     * denial in an in-band SSE error event (saiku#1454). The post-LLM re-check of the model's
     * emitted cube (saiku#1453) still runs inside {@link #askInSpace}; this only covers the
     * caller-supplied ref.
     */
    public enum SpaceAccess {
        OK,
        SPACES_NOT_CONFIGURED,
        SPACE_NOT_FOUND,
        FORBIDDEN
    }

    /**
     * Pre-flight the caller-supplied cube ref against the space's allowlist without invoking the
     * provider. Mirrors the guard sequence at the top of {@link #askInSpace} so the two agree on
     * what a scope denial is. Cheap (registry lookup only) — safe to call before streaming starts.
     */
    public SpaceAccess checkSpaceAccess(String spaceId, AiCubeRef ref) {
        if (spaces == null) {
            return SpaceAccess.SPACES_NOT_CONFIGURED;
        }
        if (spaceId == null || spaceId.isBlank()) {
            return SpaceAccess.SPACE_NOT_FOUND;
        }
        java.util.Optional<AgentSpace> maybe = spaces.get(spaceId);
        if (maybe.isEmpty()) {
            return SpaceAccess.SPACE_NOT_FOUND;
        }
        AgentSpace space = maybe.get();
        AiCubeRef effectiveRef = ref != null ? ref : space.defaultCube();
        if (effectiveRef == null || !space.allowsCube(effectiveRef)) {
            return SpaceAccess.FORBIDDEN;
        }
        return SpaceAccess.OK;
    }

    /** Fed-back result rows are capped to match the client-side cellset digest's 50-row cap. */
    private static final int CHAIN_DIGEST_MAX_ROWS = 50;

    /**
     * Cap on the number of provider round-trips {@link #askChained} will make in one call. Read
     * from env {@code SAIKU_AI_MAX_STEPS} first (so ops can tune it without a {@code -D}), else
     * system property {@code saiku.ai.ask.max-steps}, else default 4; clamped to [1, 8].
     */
    private static int maxSteps() {
        String v = System.getenv("SAIKU_AI_MAX_STEPS");
        if (v == null || v.isBlank()) v = System.getProperty("saiku.ai.ask.max-steps");
        int n = 4;
        if (v != null && !v.isBlank()) {
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignore) {
                // fall through to the default
            }
        }
        return Math.max(1, Math.min(8, n));
    }

    /**
     * When true (default), a chained turn that FOLLOWS an executed query is forced to the report
     * (emit_insight) tool only — dropping the emit_query schema (~9k tokens) so the loop fits tight
     * LLM token-per-minute limits. Set false to keep full multi-turn agency (the model may issue
     * another query mid-chain), e.g. for the dashboard builder.
     */
    private static boolean forceReportAfterQuery() {
        String v = System.getenv("SAIKU_AI_CHAIN_FORCE_REPORT");
        if (v == null || v.isBlank()) v = System.getProperty("saiku.ai.ask.chain.forceReportAfterQuery");
        return v == null || v.isBlank() || Boolean.parseBoolean(v.trim()); // default TRUE
    }

    /**
     * Max in-loop retries when the LLM returns a rate-limit (HTTP 429). Default 2; 0 disables paced
     * retry. Read from env {@code SAIKU_AI_CHAIN_RATELIMIT_RETRIES} first, else system property
     * {@code saiku.ai.ask.chain.rateLimitRetries}, clamped to [0, 5].
     */
    private static int rateLimitRetries() {
        String v = System.getenv("SAIKU_AI_CHAIN_RATELIMIT_RETRIES");
        if (v == null || v.isBlank()) v = System.getProperty("saiku.ai.ask.chain.rateLimitRetries");
        int n = 2;
        if (v != null && !v.isBlank()) {
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignore) {
                // fall through to the default
            }
        }
        return Math.max(0, Math.min(5, n));
    }

    /**
     * Hard cap on each rate-limit wait (ms). Default 20000; keeps a slow LLM from pinning the
     * request thread. Read from env {@code SAIKU_AI_CHAIN_RATELIMIT_MAX_WAIT_MS} first, else system
     * property {@code saiku.ai.ask.chain.rateLimitMaxWaitMs}, clamped to [0, 60000].
     */
    private static long rateLimitMaxWaitMs() {
        String v = System.getenv("SAIKU_AI_CHAIN_RATELIMIT_MAX_WAIT_MS");
        if (v == null || v.isBlank()) v = System.getProperty("saiku.ai.ask.chain.rateLimitMaxWaitMs");
        long n = 20_000L;
        if (v != null && !v.isBlank()) {
            try {
                n = Long.parseLong(v.trim());
            } catch (NumberFormatException ignore) {
                // fall through to the default
            }
        }
        return Math.max(0L, Math.min(60_000L, n));
    }

    /** Used when a 429 gave no usable hint ({@code retryAfterMs == 0}). */
    private static final long DEFAULT_RATE_LIMIT_WAIT_MS = 5_000L;

    /** Sleep primitive, injectable so tests exercise the paced-retry loop without real waits. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private Sleeper sleeper = Thread::sleep;

    /** Package-visible for tests. */
    void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Bounded server-side agentic loop: the model builds a query, the server executes it, the result
     * is fed back (egress-gated), and the model reports on it — all in one request. Terminal tool, a
     * degrade, or the step cap ends the loop. NO send/save/mutate capability; execution returns data
     * only to the authenticated caller under the same policy as a single-turn query.
     *
     * <p>v1 runs the classic (non-space-scoped) path only — {@code space} is always {@code null} for
     * the routed outcomes and no space system prompt / skill filtering applies.
     */
    public AskChain askChained(
            AiCubeRef ref,
            String question,
            List<NlAskMessage> history,
            String cellsetDigest,
            NlAskRequest.ForceTool forceTool,
            AiQueryRequest currentQuery) {

        if (ref == null) {
            return new AskChain(List.of(AskOutcome.degraded("cube ref required", null)), false);
        }
        if (question == null || question.isBlank()) {
            return new AskChain(List.of(AskOutcome.degraded("question must be non-blank", null)), false);
        }
        if (thinQueryService == null) { // chaining needs the executor
            return new AskChain(
                    List.of(AskOutcome.degraded("server-side query execution is not available on this instance", null)),
                    false);
        }

        // --- preamble (mirror askInternal) ---
        AiSchema schema;
        try {
            schema = metadataService.getSchema(ref);
        } catch (RuntimeException e) {
            log.warn("chained ask: failed to load schema for {}", ref, e);
            return new AskChain(List.of(AskOutcome.degraded("failed to load cube schema", null)), false);
        }
        String schemaJson;
        String requestSchemaJson;
        try {
            schemaJson = mapper.writeValueAsString(schema.toAgentView());
            requestSchemaJson = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (JsonProcessingException e) {
            return new AskChain(List.of(AskOutcome.degraded("schema serialisation failed", null)), false);
        }
        final boolean hadCellsetOnScreen = cellsetDigest != null && !cellsetDigest.isBlank();
        String effectiveDigest = cellsetDigest;
        if (hadCellsetOnScreen && !egressPermitsCellData()) effectiveDigest = null;

        String currentQueryJson = null;
        if (currentQuery != null) {
            try {
                currentQueryJson = mapper.writeValueAsString(currentQuery);
            } catch (JsonProcessingException e) {
                log.debug("chained ask: currentQuery serialise failed; omitting", e);
            }
        }
        String effectiveQuestion = maybeExpandSlashCommand(question, null);
        String skillsFragment = null;
        if (skills != null) {
            String f = AgentSkill.catalogPromptFragment(skills.list());
            if (f != null && !f.isBlank()) skillsFragment = f;
        }
        final NlAskRequest.ForceTool ft = forceTool == null ? NlAskRequest.ForceTool.AUTO : forceTool;
        final List<NlAskMessage> hist = history == null ? List.of() : history;

        // --- the loop ---
        List<AskOutcome> steps = new ArrayList<>();
        List<ToolTurn> transcript = new ArrayList<>();
        String turnDigest = effectiveDigest; // the cellset the model sees THIS turn; starts as on-screen (gated)
        int cap = maxSteps();
        boolean hitStepLimit = false;
        final boolean forceReport = forceReportAfterQuery();

        for (int i = 0; i < cap; i++) {
            // Continuation turns (a query already executed this chain) are forced to the report tool
            // when the trim toggle is on — drops the ~9k-token emit_query schema from the request the
            // provider never uses on that turn. Turn-1 (empty transcript) always uses the caller's ft
            // so it can still emit_query.
            NlAskRequest.ForceTool turnForceTool =
                    (forceReport && !transcript.isEmpty()) ? NlAskRequest.ForceTool.INSIGHT : ft;
            NlAskRequest req = new NlAskRequest(
                    ref,
                    effectiveQuestion,
                    schemaJson,
                    requestSchemaJson,
                    hist,
                    turnDigest,
                    turnForceTool,
                    currentQueryJson,
                    skillsFragment,
                    null,
                    List.copyOf(transcript));
            // OPT-3: a rate-limited turn (HTTP 429) is paced + retried in place — the SAME request,
            // since a rate limit isn't the model's fault — up to a bounded retry count. A non-429
            // degrade never enters that loop. Shared with buildDashboard (see askWithPacedRetry).
            NlAskResponse resp = askWithPacedRetry(req);

            if (resp.degraded()) {
                steps.add(AskOutcome.degraded(resp.reason(), resp.model()));
                break;
            }

            AskOutcome outcome = routeResponse(resp, null, hadCellsetOnScreen);
            steps.add(outcome);

            boolean isQuery = outcome.kind() == AskOutcome.Kind.QUERY && !outcome.degraded();
            if (!isQuery) break; // terminal report / degraded route → done

            if (i == cap - 1) {
                hitStepLimit = true; // cap reached still building queries
                break;
            }

            // SEC: never execute or digest when egress is denied — schema-only stops honestly here
            // (also avoids a wasted Mondrian execution).
            if (!egressPermitsCellData()) {
                steps.add(AskOutcome.degraded(
                        "Query built. Enable aggregated AI egress (SAIKU_AI_LLM_EGRESS=aggregated) for an "
                                + "AI report on the results.",
                        resp.model()));
                break;
            }
            AiQueryRequest parsed = outcome.request();
            CellDataSet cds;
            try {
                AiSchema qSchema = metadataService.getSchema(parsed.getCube());
                ThinQuery tq = converter.convert(parsed, qSchema);
                cds = thinQueryService.execute(tq);
            } catch (RuntimeException e) {
                log.warn("chained ask: query execution failed", e);
                steps.add(AskOutcome.degraded("failed to execute the built query", resp.model()));
                break;
            }
            // SEC: k-anonymity small-cell suppression on the executed result before it crosses to
            // the LLM — parity with buildResponse's applyKAnonymity on every other egress path.
            if (kAnonymityFilter != null) {
                kAnonymityFilter.applyToCellDataSet(cds);
            }
            String resultDigest = CellsetDigestBuilder.digest(cds, CHAIN_DIGEST_MAX_ROWS);
            String callId =
                    (resp.toolCallId() != null && !resp.toolCallId().isBlank()) ? resp.toolCallId() : "call_" + i;
            transcript.add(new ToolTurn(callId, "emit_query", resp.payloadJson(), resultDigest));
            turnDigest = resultDigest; // feed forward: unlocks emit_insight + supplies the data
            // else: loop again — the model now has the data and should emit_insight (the report).
        }
        return new AskChain(List.copyOf(steps), hitStepLimit);
    }

    /**
     * Send {@code req} to the provider and pace-retry it in place on a rate-limit (HTTP 429) or a
     * transient tool-call failure — the SAME request, since neither is the model's fault — up to a
     * bounded retry count. A non-429 degrade ({@code retryAfterMs == -1}) returns immediately.
     * Extracted so {@link #askChained} and {@link #buildDashboard} share identical paced-retry
     * behaviour rather than duplicating the loop.
     */
    private NlAskResponse askWithPacedRetry(NlAskRequest req) {
        NlAskResponse resp = provider.ask(req);
        int rlRetries = 0;
        while (resp.degraded() && resp.retryAfterMs() >= 0 && rlRetries < rateLimitRetries()) {
            long wait = resp.retryAfterMs() > 0 ? resp.retryAfterMs() : DEFAULT_RATE_LIMIT_WAIT_MS;
            wait = Math.min(wait, rateLimitMaxWaitMs());
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break; // stop retrying; caller handles the degraded response
            }
            rlRetries++;
            log.info("AI ask: LLM rate-limited, waited {}ms, retry {}/{}", wait, rlRetries, rateLimitRetries());
            resp = provider.ask(req);
        }
        return resp;
    }

    /**
     * Cap on the number of tiles {@link #buildDashboard} will keep. Read from env
     * {@code SAIKU_AI_DASHBOARD_MAX_TILES} first (so ops can tune it without a {@code -D}), else
     * system property {@code saiku.ai.dashboard.maxTiles}, else default 6; clamped to [1, 12].
     * Mirrors the {@link #maxSteps()} config-read style.
     */
    private static int maxTiles() {
        String v = System.getenv("SAIKU_AI_DASHBOARD_MAX_TILES");
        if (v == null || v.isBlank()) v = System.getProperty("saiku.ai.dashboard.maxTiles");
        int n = 6;
        if (v != null && !v.isBlank()) {
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignore) {
                // fall through to the default
            }
        }
        return Math.max(1, Math.min(12, n));
    }

    /** Allowlisted dashboard tile kinds (MVP). Unknown values coerce to {@code table}. */
    private static final java.util.Set<String> TILE_TYPES = java.util.Set.of("chart", "table", "kpi");

    /** Allowlisted chart subtypes for chart tiles. Unknown values coerce to {@code bar}. */
    private static final java.util.Set<String> CHART_TYPES = java.util.Set.of("bar", "line", "pie", "area", "scatter");

    private static final int DASHBOARD_TITLE_MAX = 120;
    private static final int TILE_TITLE_MAX = 80;

    /**
     * Build a multi-tile dashboard spec from one natural-language request. A separate, simpler entry
     * point than {@link #askChained}: a dashboard needs no execute→feedback grounding loop because
     * the model authors query SPECS from the schema alone — NO cell data is ever executed or fed
     * back to the LLM on this path (tiles run lazily client-side; here we only prove each tile query
     * CONVERTS against the live cube).
     *
     * <p>The model's output is untrusted: every tile is hardened server-side (§3 of the design) —
     * cube-pinned to {@code ref}, converter-validated (hallucinated dims/levels/members drop the
     * tile), title-sanitised, and type/chartType-coerced to their allowlists. The tile set is capped
     * at {@link #maxTiles()} (excess truncated, not errored). If nothing survives, a clean degrade.
     *
     * @param ref cube to target; must be non-null with a non-null cubeName
     * @param question free-form English request; must be non-blank
     * @param history prior turns; may be null / empty
     * @param space optional agent-space persona (system prompt + skill filter + cube allowlist); may
     *     be null for the classic, non-space-scoped path
     */
    public DashboardSpec buildDashboard(AiCubeRef ref, String question, List<NlAskMessage> history, AgentSpace space) {
        if (ref == null) {
            return DashboardSpec.degraded("cube ref required", null);
        }
        if (question == null || question.isBlank()) {
            return DashboardSpec.degraded("question must be non-blank", null);
        }
        // Persona guardrail: if a space is in effect, its allowlist pins the cube. The web layer
        // resolves ref within the allowlist before calling; re-check here so a stale ref can't slip
        // through (mirrors askInSpace's guard).
        if (space != null && !space.allowsCube(ref)) {
            return DashboardSpec.degraded(
                    "FORBIDDEN: cube " + ref.getCubeName() + " is not in space '" + space.id() + "' allowlist", null);
        }

        AiSchema schema;
        try {
            schema = metadataService.getSchema(ref);
        } catch (RuntimeException e) {
            log.warn("dashboard build: failed to load schema for {}", ref, e);
            // Generic client-facing reason — the exception detail is logged, never echoed
            // (#1282-class info-leak hardening), mirroring askInternal.
            return DashboardSpec.degraded("failed to load cube schema", null);
        }

        String schemaJson;
        String requestSchemaJson;
        try {
            // Always the PII-filtered agent view (#3) — schema egress is permitted, but must stay
            // filtered. This path sends NO cell data, so it isn't gated on the aggregated egress
            // posture the report path uses.
            schemaJson = mapper.writeValueAsString(schema.toAgentView());
            requestSchemaJson = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (JsonProcessingException e) {
            log.warn("dashboard build: schema serialisation failed", e);
            return DashboardSpec.degraded("schema serialisation failed", null);
        }

        String effectiveQuestion = maybeExpandSlashCommand(question, space);
        String skillsFragment = null;
        if (skills != null) {
            List<AgentSkill> catalog = skills.list();
            if (space != null) {
                catalog = catalog.stream()
                        .filter(s -> space.allowsSkill(s.name()))
                        .toList();
            }
            String f = AgentSkill.catalogPromptFragment(catalog);
            if (f != null && !f.isBlank()) skillsFragment = f;
        }
        String spaceSystemPrompt = space != null ? space.systemPrompt() : null;

        NlAskRequest req = new NlAskRequest(
                ref,
                effectiveQuestion,
                schemaJson,
                requestSchemaJson,
                history == null ? List.of() : history,
                null, // no cellset digest — the dashboard is authored from the schema alone
                NlAskRequest.ForceTool.DASHBOARD,
                null,
                skillsFragment,
                spaceSystemPrompt,
                List.of());

        NlAskResponse resp = askWithPacedRetry(req);
        if (resp.degraded()) {
            // Provider transport/parse/refusal degrade — surface the reason (already generic /
            // OFF_TOPIC-prefixed) so the resource can render it.
            return DashboardSpec.degraded(resp.reason(), resp.model());
        }
        if (resp.kind() != NlAskResponse.Kind.DASHBOARD || resp.payloadJson() == null) {
            return DashboardSpec.degraded("provider did not return a dashboard", resp.model());
        }
        return hardenDashboard(resp.payloadJson(), ref, schema, resp.model());
    }

    /**
     * Parse the raw {@code emit_dashboard} payload and apply ALL server-side hardening (§3). The
     * model's JSON is untrusted, so this drives the whole validation surface: tile cap + truncation,
     * per-tile converter validation, cube-pinning, title sanitisation, and type/chartType coercion.
     */
    private DashboardSpec hardenDashboard(String payloadJson, AiCubeRef ref, AiSchema schema, String model) {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = mapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            log.warn("dashboard build: provider emitted invalid JSON: {}", e.getMessage());
            return DashboardSpec.degraded("provider emitted invalid dashboard JSON", model);
        }

        String title = sanitizeLabel(
                root.path("title").isTextual() ? root.path("title").asText() : null,
                DASHBOARD_TITLE_MAX,
                "AI dashboard");

        com.fasterxml.jackson.databind.JsonNode tilesNode = root.path("tiles");
        int cap = maxTiles();
        int total = tilesNode.isArray() ? tilesNode.size() : 0;
        if (total > cap) {
            // Truncate to the cap — never error on an over-eager model (§3).
            log.info("dashboard build: model returned {} tiles, capping to {} (dropped {})", total, cap, total - cap);
        }

        List<DashboardTileSpec> tiles = new ArrayList<>();
        int considered = Math.min(total, cap);
        for (int i = 0; i < considered; i++) {
            com.fasterxml.jackson.databind.JsonNode tileNode = tilesNode.get(i);
            if (tileNode == null || !tileNode.isObject()) {
                continue;
            }
            AiQueryRequest query;
            try {
                query = mapper.treeToValue(tileNode.path("query"), AiQueryRequest.class);
            } catch (JsonProcessingException e) {
                log.warn("dashboard build: tile {} query failed to parse — dropping tile", i);
                continue;
            }
            if (query == null) {
                log.warn("dashboard build: tile {} has no query — dropping tile", i);
                continue;
            }
            // Cube lock: force every tile to the session cube. The model must not author a tile
            // against a different cube; ignore/override whatever cube it put on the tile (§3).
            query.setCube(ref);
            // Per-tile validation: prove the query CONVERTS against the live cube. This rejects
            // hallucinated dimensions/levels/members. We do NOT execute (thinQueryService.execute) —
            // tiles run lazily client-side, so NO cell data crosses to the LLM on this path.
            try {
                converter.convert(query, schema);
            } catch (RuntimeException e) {
                // Generic warn — the converter's detail (which can echo member/level text) stays out
                // of the client-facing spec (#1282-class hardening). Drop the tile, keep the rest.
                log.warn("dashboard build: tile {} query did not validate against the cube — dropping tile", i);
                continue;
            }

            String tileTitle = sanitizeLabel(
                    tileNode.path("title").isTextual() ? tileNode.path("title").asText() : null,
                    TILE_TITLE_MAX,
                    "Tile " + (tiles.size() + 1));
            String type = coerceType(tileNode.path("type").asText(null));
            String chartType = coerceChartType(type, tileNode.path("chartType").asText(null));
            tiles.add(new DashboardTileSpec(tileTitle, type, chartType, query));
        }

        if (tiles.isEmpty()) {
            return DashboardSpec.degraded("couldn't build a dashboard for that request", model);
        }
        return DashboardSpec.ok(title, tiles, model);
    }

    /** Coerce a tile type to the {chart, table, kpi} allowlist; unknown/blank → {@code table}. */
    private static String coerceType(String raw) {
        if (raw != null && TILE_TYPES.contains(raw)) {
            return raw;
        }
        return "table";
    }

    /**
     * Coerce a chart subtype: {@code null} for non-chart tiles; for chart tiles, an allowlisted
     * value or {@code bar} when missing/unknown.
     */
    private static String coerceChartType(String type, String raw) {
        if (!"chart".equals(type)) {
            return null;
        }
        if (raw != null && CHART_TYPES.contains(raw)) {
            return raw;
        }
        return "bar";
    }

    /**
     * Sanitise a plain-text label from untrusted model output: strip HTML tags and control chars
     * (including CR/LF), collapse runs of whitespace, and cap the length. Blank input (or a value
     * that sanitises to empty) falls back to {@code fallback}. No new dependency — these are text
     * labels, not HTML, so the OWASP sanitizer would be overkill.
     */
    private static String sanitizeLabel(String raw, int maxLen, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.replaceAll("<[^>]*>", " "); // strip tag-like spans
        // Strip control chars (incl. CR/LF/tab, \p{Cntrl}) AND Unicode format chars (\p{Cf} —
        // bidi overrides like U+202E, zero-width joiners/non-joiners U+200B–U+200D, BOM U+FEFF,
        // LRM/RLM). The latter are invisible, so a Trojan-Source visual spoof can't survive in a
        // plain-text label. Normal letters/digits/punctuation/spaces and non-Latin scripts stay.
        s = s.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        s = s.trim().replaceAll("\\s+", " "); // collapse whitespace
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen).trim();
        }
        return s;
    }

    private AskOutcome askInternal(
            AiCubeRef ref,
            String question,
            List<NlAskMessage> history,
            String cellsetDigest,
            NlAskRequest.ForceTool forceTool,
            AiQueryRequest currentQuery,
            AgentSpace space) {
        if (ref == null) {
            return AskOutcome.degraded("cube ref required", null);
        }
        if (question == null || question.isBlank()) {
            return AskOutcome.degraded("question must be non-blank", null);
        }

        AiSchema schema;
        try {
            schema = metadataService.getSchema(ref);
        } catch (RuntimeException e) {
            log.warn("Failed to load schema for {} — cannot ask AI", ref, e);
            // Generic client-facing reason — the exception detail (datasource / JDBC error text)
            // is logged above, never echoed to the caller (#1282-class info-leak hardening).
            return AskOutcome.degraded("failed to load cube schema", null);
        }

        String schemaJson;
        String requestSchemaJson;
        try {
            // #3: serialise the PII-filtered agent view, never the raw schema. PII-tagged levels /
            // measures have their captions, sample members, descriptions and synonyms stripped before
            // the schema crosses the trust boundary to the vendor. Applies at every egress tier —
            // schema egress is permitted even at schema-only, but must still be PII-filtered.
            schemaJson = mapper.writeValueAsString(schema.toAgentView());
            requestSchemaJson = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise schema / request-schema for ask", e);
            return AskOutcome.degraded("schema serialisation failed", null);
        }

        // No-data guard (design §5): capture whether the CLIENT actually sent a digest BEFORE the
        // egress-policy strip below mutates it. This must not be conflated with the post-strip
        // value — under schema-only egress a digest that WAS on screen gets nulled for the LLM, but
        // that's "data present, withheld by policy", not "no analysis on screen". Only the latter
        // should refuse EMAIL_DRAFT (see the EMAIL_DRAFT routing branch further down).
        final boolean hadCellsetOnScreen = cellsetDigest != null && !cellsetDigest.isBlank();

        // #2: LLM-egress gate. When the dedicated egress guard does NOT permit aggregated cell
        // values to leave the box, STRIP the cellset digest so the prompt is schema-only. The ask
        // still runs — degraded (ungrounded), never refused. Fail-closed: an unwired guard denies
        // egress, so any doubt strips.
        String effectiveDigest = cellsetDigest;
        if (cellsetDigest != null && !cellsetDigest.isBlank() && !egressPermitsCellData()) {
            effectiveDigest = null;
            // No data in the log — just the fact that the digest was withheld by policy.
            log.debug("Cellset digest withheld from LLM by egress policy; ask proceeds schema-only");
        }

        // Serialise the current query into JSON for the provider to embed in the prompt.
        // Null/blank when absent — providers omit the "Current query" context block entirely
        // rather than say "Current query: null", which would just bloat the prompt.
        String currentQueryJson = null;
        if (currentQuery != null) {
            try {
                currentQueryJson = mapper.writeValueAsString(currentQuery);
            } catch (JsonProcessingException e) {
                log.debug("Failed to serialise currentQuery for ask context; omitting", e);
            }
        }
        // Slash-command routing: when the ask starts with `/<skill-name>` and that skill exists,
        // prefix the user's remaining ask with the skill body. The LLM then sees both the skill
        // steps and the user's follow-up (e.g. `/weekly-rollup last quarter instead of this week`)
        // and can adapt. Skills whose slug doesn't match fall through unchanged. When routing
        // through a space, the space's skillAllowlist gates which skills the slash-router (and the
        // catalogue below) can reach — a user asking `/weekly-rollup` in a space that doesn't
        // allowlist that skill falls through as a raw ask, same as if the skill didn't exist.
        String effectiveQuestion = maybeExpandSlashCommand(question, space);
        String skillsFragment = null;
        if (skills != null) {
            List<AgentSkill> catalog = skills.list();
            if (space != null) {
                catalog = catalog.stream()
                        .filter(s -> space.allowsSkill(s.name()))
                        .toList();
            }
            skillsFragment = AgentSkill.catalogPromptFragment(catalog);
            if (skillsFragment == null || skillsFragment.isBlank()) {
                skillsFragment = null;
            }
        }
        // Space system prompt is prepended by the provider to the built-in SYSTEM_PROMPT — the
        // persona voice ("You are the FoodMart Sales Analyst…") without giving up the tool-choice
        // rails that make the ask surface safe.
        String spaceSystemPrompt = space != null ? space.systemPrompt() : null;

        NlAskRequest req = new NlAskRequest(
                ref,
                effectiveQuestion,
                schemaJson,
                requestSchemaJson,
                history == null ? List.of() : history,
                effectiveDigest,
                forceTool == null ? NlAskRequest.ForceTool.AUTO : forceTool,
                currentQueryJson,
                skillsFragment,
                spaceSystemPrompt,
                List.of());
        NlAskResponse resp = provider.ask(req);

        if (resp.degraded()) {
            return AskOutcome.degraded(resp.reason(), resp.model());
        }
        return routeResponse(resp, space, hadCellsetOnScreen);
    }

    /**
     * Map a NON-degraded provider response to an {@link AskOutcome} by which tool the provider's
     * model picked. Self-contained: handles its own JSON parse errors and space cube-scope
     * re-validation. {@code space} may be {@code null} (classic, non-space-scoped path).
     * {@code hadCellsetOnScreen} drives the {@link AskOutcome.Kind#EMAIL_DRAFT} no-data guard.
     */
    private AskOutcome routeResponse(NlAskResponse resp, AgentSpace space, boolean hadCellsetOnScreen) {
        try {
            NlAskResponse.Kind kind = resp.kind();
            if (kind == NlAskResponse.Kind.QUERY) {
                AiQueryRequest parsed = mapper.readValue(resp.payloadJson(), AiQueryRequest.class);
                // Persona guardrail (saiku#1453/#1463): the pre-LLM check at askInSpace validated
                // only the INPUT ref. The model's emitted query names its own cube, which a
                // prompt-injected question can push outside the allowlist. Re-validate the executed
                // cube here — this is the only place it can live, because AskOutcome carries no
                // space context, so a downstream resource (e.g. the streaming endpoint that
                // executes the query) cannot re-check it.
                if (space != null && !space.allowsCube(parsed.getCube())) {
                    String cubeName = parsed.getCube() == null
                            ? "(none)"
                            : parsed.getCube().getCubeName();
                    log.warn(
                            "Space '{}' scope violation: model emitted a query against non-allowlisted cube {}",
                            space.id(),
                            cubeName);
                    return AskOutcome.degraded(
                            "FORBIDDEN: cube " + cubeName + " is not in space '" + space.id() + "' allowlist",
                            resp.model(),
                            SpaceAccess.FORBIDDEN);
                }
                return AskOutcome.ok(parsed, resp.model());
            }
            if (kind == NlAskResponse.Kind.INSIGHT) {
                AiInsight insight = mapper.readValue(resp.payloadJson(), AiInsight.class);
                if (insight == null
                        || insight.getMarkdown() == null
                        || insight.getMarkdown().isBlank()) {
                    return AskOutcome.degraded("provider emitted empty insight", resp.model());
                }
                return AskOutcome.okInsight(insight, resp.model());
            }
            if (kind == NlAskResponse.Kind.EMAIL_DRAFT) {
                if (!hadCellsetOnScreen) {
                    return AskOutcome.degraded(
                            "No analysis is on screen to summarise — run a query first, then ask me to email it.",
                            resp.model());
                }
                AiEmailDraft draft = mapper.readValue(resp.payloadJson(), AiEmailDraft.class);
                if (draft == null
                        || draft.getSummary() == null
                        || draft.getSummary().isBlank()) {
                    return AskOutcome.degraded("provider emitted empty email draft", resp.model());
                }
                return AskOutcome.okEmailDraft(draft, resp.model());
            }
            if (kind == NlAskResponse.Kind.VIEW_CHANGE) {
                AiViewChange vc = mapper.readValue(resp.payloadJson(), AiViewChange.class);
                if (vc == null
                        || vc.getViewMode() == null
                        || !AiViewChangeCatalog.VIEW_MODES.contains(vc.getViewMode())) {
                    return AskOutcome.degraded("provider emitted invalid viewMode", resp.model());
                }
                if (vc.getChartType() != null
                        && !vc.getChartType().isBlank()
                        && !AiViewChangeCatalog.CHART_TYPE_IDS.contains(vc.getChartType())) {
                    return AskOutcome.degraded(
                            "provider emitted unknown chartType '" + vc.getChartType() + "'", resp.model());
                }
                return AskOutcome.okViewChange(vc, resp.model());
            }
            return AskOutcome.degraded("provider returned unexpected kind: " + kind, resp.model());
        } catch (JsonProcessingException e) {
            log.warn("Provider returned invalid JSON for kind={}: {}", resp.kind(), e.getMessage());
            // Generic client-facing reason — the parser detail is logged above, not echoed
            // to the caller (#1282-class info-leak hardening). Kind is a safe enum.
            return AskOutcome.degraded("provider emitted invalid JSON for " + resp.kind(), resp.model());
        }
    }

    /**
     * If {@code question} starts with {@code /<skill-name>} (kebab-case, up to whitespace), and
     * the registry knows that skill, expand it to {@code <skill body> + <remainder of question>}.
     * When {@code space} is non-null the skill must also be in the space's allowlist — otherwise
     * the slash falls through unchanged so the LLM sees the raw ask.
     */
    private String maybeExpandSlashCommand(String question, AgentSpace space) {
        if (skills == null || question == null || !question.startsWith("/")) {
            return question;
        }
        int split = question.indexOf(' ');
        String slug = split < 0 ? question.substring(1) : question.substring(1, split);
        if (slug.isBlank()) {
            return question;
        }
        if (space != null && !space.allowsSkill(slug)) {
            return question;
        }
        return skills.get(slug)
                .map(skill -> {
                    String remainder =
                            split < 0 ? "" : question.substring(split).trim();
                    StringBuilder out = new StringBuilder();
                    out.append("Skill: ").append(skill.name()).append('\n');
                    out.append(skill.body().trim());
                    if (!remainder.isEmpty()) {
                        out.append("\n\nUser follow-up: ").append(remainder);
                    }
                    return out.toString();
                })
                .orElse(question);
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper m = new ObjectMapper();
        // Keep the schema-as-context terse — Saiku's AiSchema toggles already control includes.
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }
}
