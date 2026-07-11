/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Objects;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiRequestJsonSchema;
import org.saiku.service.olap.ai.AiSchema;
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
     * <p>Exactly one of {@code request} / {@code insight} / {@code viewChange} is non-null on
     * success (matched to {@link #kind()}); all are null on degraded.
     */
    public record AskOutcome(
            Kind kind,
            boolean degraded,
            String reason,
            AiQueryRequest request,
            AiInsight insight,
            AiViewChange viewChange,
            String model) {

        public enum Kind {
            QUERY,
            INSIGHT,
            VIEW_CHANGE
        }

        public static AskOutcome ok(AiQueryRequest request, String model) {
            return new AskOutcome(Kind.QUERY, false, null, request, null, null, model);
        }

        public static AskOutcome okInsight(AiInsight insight, String model) {
            return new AskOutcome(Kind.INSIGHT, false, null, null, insight, null, model);
        }

        public static AskOutcome okViewChange(AiViewChange viewChange, String model) {
            return new AskOutcome(Kind.VIEW_CHANGE, false, null, null, null, viewChange, model);
        }

        public static AskOutcome degraded(String reason, String model) {
            return new AskOutcome(null, true, reason, null, null, null, model);
        }
    }

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
            return AskOutcome.degraded("agent spaces are not configured on this instance", null);
        }
        if (spaceId == null || spaceId.isBlank()) {
            return AskOutcome.degraded("space id required", null);
        }
        java.util.Optional<AgentSpace> maybe = spaces.get(spaceId);
        if (maybe.isEmpty()) {
            return AskOutcome.degraded("space not found: " + spaceId, null);
        }
        AgentSpace space = maybe.get();
        AiCubeRef effectiveRef = ref != null ? ref : space.defaultCube();
        if (effectiveRef == null) {
            return AskOutcome.degraded("space has no cubes in its allowlist", null);
        }
        if (!space.allowsCube(effectiveRef)) {
            // Persona guardrail — the caller tried to point the space at a cube outside its
            // allowlist. Deny loudly rather than silently rewrite to the default; a UI that hands
            // us a stale cube ref should get corrected, not silently reinterpreted.
            return AskOutcome.degraded(
                    "FORBIDDEN: cube " + effectiveRef.getCubeName() + " is not in space '" + spaceId + "' allowlist",
                    null);
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
            schemaJson = mapper.writeValueAsString(schema);
            requestSchemaJson = mapper.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise schema / request-schema for ask", e);
            return AskOutcome.degraded("schema serialisation failed", null);
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
                cellsetDigest,
                forceTool == null ? NlAskRequest.ForceTool.AUTO : forceTool,
                currentQueryJson,
                skillsFragment,
                spaceSystemPrompt);
        NlAskResponse resp = provider.ask(req);

        if (resp.degraded()) {
            return AskOutcome.degraded(resp.reason(), resp.model());
        }

        // Route by which tool the provider's model picked.
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
                            resp.model());
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
