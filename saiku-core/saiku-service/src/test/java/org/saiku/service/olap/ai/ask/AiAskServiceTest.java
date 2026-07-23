/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiSchema;

/** Unit tests for {@link AiAskService}. Provider + metadata service are stubbed. */
public class AiAskServiceTest {

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");

    @Test
    public void degradesWhenCubeRefNull() {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(null, "q", List.of());
        assertTrue(out.degraded());
        assertEquals("cube ref required", out.reason());
    }

    @Test
    public void degradesWhenQuestionBlank() {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "   ", List.of());
        assertTrue(out.degraded());
        assertEquals("question must be non-blank", out.reason());
    }

    @Test
    public void degradesWhenSchemaLoadThrows() {
        AiAskService svc = new AiAskService(
                ref -> {
                    throw new RuntimeException("cube not found");
                },
                stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        // #1282-class hardening: the client-facing reason is generic; the raw exception detail
        // ("cube not found", which can carry datasource / JDBC text) is logged server-side only.
        assertTrue(out.reason().contains("failed to load cube schema"));
        assertFalse(out.reason().contains("cube not found"));
    }

    @Test
    public void degradesWhenProviderDegrades() {
        AiAskService svc =
                new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.degraded("not configured")));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        assertEquals("not configured", out.reason());
        assertNull(out.request());
    }

    @Test
    public void degradesWhenProviderEmitsInvalidJson() {
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("not-valid-json", "claude-x", 1, 1)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales", List.of());
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("provider emitted invalid JSON"));
        assertEquals("claude-x", out.model());
    }

    @Test
    public void returnsParsedAiQueryRequestOnSuccess() {
        String emittedJson = "{"
                + "\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"},"
                + "\"measures\":[{\"name\":\"Store Sales\"}],"
                + "\"rows\":[{\"dimension\":\"Customers\",\"level\":\"Country\"}]"
                + "}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok(emittedJson, "claude-x", 100, 50)));

        AiAskService.AskOutcome out = svc.ask(CUBE, "show sales by country", List.of());

        assertFalse(out.degraded());
        assertNotNull(out.request());
        assertEquals("Sales", out.request().getCube().getCubeName());
        assertEquals(1, out.request().getMeasures().size());
        assertEquals("Store Sales", out.request().getMeasures().get(0).getName());
        assertEquals("claude-x", out.model());
    }

    @Test
    public void passesQuestionAndHistoryToProviderUnchanged() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);

        svc.ask(CUBE, "show sales by country", List.of(NlAskMessage.user("earlier"), NlAskMessage.assistant("ack")));

        NlAskRequest captured = seen.get();
        assertNotNull(captured);
        assertEquals("show sales by country", captured.question());
        assertEquals(2, captured.history().size());
        assertEquals(CUBE, captured.cubeRef());
        // Schema + request schema should both be non-empty JSON.
        assertTrue(captured.cubeSchemaJson().startsWith("{"));
        assertTrue(captured.requestJsonSchema().contains("AiQueryRequest"));
    }

    /* ---- saiku#1365 view-change allowlist (the LLM can't inject an arbitrary view) ---- */

    @Test
    public void acceptsAViewChangeWithAnAllowlistedChartType() {
        // A view-change whose chartType is in AiViewChangeCatalog.CHART_TYPE_IDS is
        // accepted (not degraded) and surfaced for the UI to apply.
        String json = "{\"viewMode\":\"chart\",\"chartType\":\"bar\",\"reason\":\"trend over time\"}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.okViewChange(json, "claude-x", 1, 1)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "make it a bar chart", List.of());
        assertFalse(out.degraded());
        assertNotNull(out.viewChange());
        assertEquals("chart", out.viewChange().getViewMode());
        assertEquals("bar", out.viewChange().getChartType());
    }

    @Test
    public void rejectsAHallucinatedChartType() {
        // The guardrail: anything outside the chartType allowlist is rejected
        // (degraded), so a hallucinated id can never reach the UI render path.
        // RED if the !CHART_TYPE_IDS.contains() guard at AiAskService were dropped.
        String json = "{\"viewMode\":\"chart\",\"chartType\":\"totally-made-up-3d-globe\"}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.okViewChange(json, "claude-x", 1, 1)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "switch view", List.of());
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("unknown chartType"));
        assertNull("a rejected view-change must not surface to the UI", out.viewChange());
    }

    @Test
    public void rejectsAnInvalidViewMode() {
        // viewMode is likewise allowlisted (grid | chart) — a bogus mode degrades.
        String json = "{\"viewMode\":\"hologram\",\"chartType\":\"bar\"}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.okViewChange(json, "claude-x", 1, 1)));
        AiAskService.AskOutcome out = svc.ask(CUBE, "switch view", List.of());
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("invalid viewMode"));
    }

    /* ---- NL email-draft intent (draft-only — the AI never sends) ---- */

    @Test
    public void returnsParsedEmailDraftOnSuccess() {
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()),
                stub(NlAskResponse.okEmailDraft("{\"summary\":\"Large tier leads at 7,000.\"}", "m", 0, 0)));

        // No-data guard (design §5): EMAIL_DRAFT only succeeds when the client actually sent a
        // cellset digest (analysis on screen) — exercise the overload that carries one.
        AiAskService.AskOutcome out =
                svc.ask(CUBE, "draft an email summarising this", List.of(), "| Tier | Balance |\n| Large | 7,000 |");

        assertFalse(out.degraded());
        assertEquals(AiAskService.AskOutcome.Kind.EMAIL_DRAFT, out.kind());
        assertNotNull(out.emailDraft());
        assertEquals("Large tier leads at 7,000.", out.emailDraft().getSummary());
    }

    @Test
    public void degradesWhenEmailDraftSummaryBlank() {
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.okEmailDraft("{\"summary\":\"\"}", "m", 0, 0)));

        // Non-blank digest so this exercises the empty-summary guard, not the no-data guard.
        AiAskService.AskOutcome out =
                svc.ask(CUBE, "draft an email summarising this", List.of(), "| Tier | Balance |\n| Large | 7,000 |");

        assertTrue(out.degraded());
        assertTrue(out.reason().contains("empty email draft"));
        assertNull(out.emailDraft());
    }

    @Test
    public void refusesEmailDraftWhenNoCellsetOnScreen() {
        // Design §5 belt-and-braces: the server refuses EMAIL_DRAFT when the client sent no
        // cellset digest at all (no analysis on screen), BEFORE the egress-strip could otherwise
        // conflate this with a digest withheld by policy. Checked here regardless of what the
        // provider stub would return.
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()),
                stub(NlAskResponse.okEmailDraft("{\"summary\":\"Large tier leads at 7,000.\"}", "m", 0, 0)));

        AiAskService.AskOutcome out = svc.ask(CUBE, "draft an email summarising this", List.of(), null);

        assertTrue(out.degraded());
        assertTrue(out.reason().contains("run a query first"));
        assertNull(out.emailDraft());
    }

    @Test
    public void draftsEmailWhenDigestPresentButEgressStripped() {
        // Regression guard for the capture-before-strip invariant: the CLIENT sent a non-blank
        // digest (there WAS analysis on screen), but schema-only egress strips it before it reaches
        // the LLM. That must NOT be conflated with "client sent nothing" — hadCellsetOnScreen is
        // captured before the strip, so EMAIL_DRAFT still succeeds. If someone later moved that
        // capture to read the post-strip value, this test would fail.
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()),
                stub(NlAskResponse.okEmailDraft("{\"summary\":\"Large tier leads at 7,000.\"}", "m", 0, 0)));
        svc.setEgressGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));

        AiAskService.AskOutcome out =
                svc.ask(CUBE, "draft an email summarising this", List.of(), "| Tier | Balance |\n| Large | 7,000 |");

        assertFalse(out.degraded());
        assertEquals(AiAskService.AskOutcome.Kind.EMAIL_DRAFT, out.kind());
        assertNotNull(out.emailDraft());
        assertEquals("Large tier leads at 7,000.", out.emailDraft().getSummary());
    }

    /* ---- saiku#1426 skill catalogue + slash-command expansion ---- */

    @Test
    public void injectsSkillsFragmentIntoRequest() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        svc.setSkills(new AgentSkillRegistry(java.nio.file.Path.of("/does/not/exist")));
        // Force a non-empty registry by pointing at a real tmp dir with one skill.
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("skills");
        java.nio.file.Files.writeString(
                tmp.resolve("weekly-rollup.md"), "---\nname: weekly-rollup\ndescription: Weekly rollup\n---\n\nbody\n");
        svc.setSkills(new AgentSkillRegistry(tmp));

        svc.ask(CUBE, "show sales", List.of());

        NlAskRequest captured = seen.get();
        assertNotNull(captured);
        assertNotNull(captured.skillsFragment());
        assertTrue(captured.skillsFragment().contains("/weekly-rollup"));
        assertTrue(captured.skillsFragment().contains("Weekly rollup"));
    }

    @Test
    public void expandsSlashCommandIntoQuestion() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("skills");
        java.nio.file.Files.writeString(
                tmp.resolve("weekly-rollup.md"),
                "---\nname: weekly-rollup\ndescription: Weekly rollup\n---\n\n1. Query totals.\n2. Compare.\n");
        svc.setSkills(new AgentSkillRegistry(tmp));

        svc.ask(CUBE, "/weekly-rollup for Q4 instead", List.of());

        NlAskRequest captured = seen.get();
        assertNotNull(captured);
        assertTrue(captured.question().contains("Skill: weekly-rollup"));
        assertTrue(captured.question().contains("Query totals"));
        assertTrue(captured.question().contains("User follow-up: for Q4 instead"));
    }

    @Test
    public void slashCommandFallsThroughWhenSkillMissing() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("skills");
        svc.setSkills(new AgentSkillRegistry(tmp));

        svc.ask(CUBE, "/nonexistent-skill for Q4", List.of());

        NlAskRequest captured = seen.get();
        assertNotNull(captured);
        // Unchanged — the slash prefix survives so the LLM can interpret it as a raw ask.
        assertEquals("/nonexistent-skill for Q4", captured.question());
    }

    @Test
    public void skillsFragmentNullWhenNoRegistry() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        // No setSkills() call — legacy path.

        svc.ask(CUBE, "show sales", List.of());

        assertNull(seen.get().skillsFragment());
    }

    /* ---- saiku#1440 agent-space scope enforcement ---- */

    @Test
    public void spaceScopedAskDegradesWithoutRegistry() throws Exception {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        AiAskService.AskOutcome out =
                svc.askInSpace("sales-analyst", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("agent spaces are not configured"));
    }

    @Test
    public void spaceScopedAskDegradesForMissingSpace() throws Exception {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("spaces");
        svc.setSpaces(new AgentSpaceRegistry(tmp));

        AiAskService.AskOutcome out = svc.askInSpace(
                "does-not-exist", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("space not found"));
    }

    @Test
    public void spaceScopedAskEnforcesAllowlist() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        // Emit the full 4-coordinate cube the real model returns (AiRequestJsonSchema marks all
        // four required) so the post-LLM allowlist re-check (saiku#1453) sees an allowlisted cube.
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok(
                    "{\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"},"
                            + "\"measures\":[]}",
                    "m",
                    0,
                    0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("spaces");
        java.nio.file.Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"systemPrompt\":\"You are the Sales Analyst.\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"}"
                        + "]}");
        svc.setSpaces(new AgentSpaceRegistry(tmp));

        // In-allowlist ref: allowed.
        AiAskService.AskOutcome allowed =
                svc.askInSpace("sales-analyst", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);
        assertFalse(allowed.degraded());
        assertNotNull(seen.get());
        assertEquals("You are the Sales Analyst.", seen.get().spaceSystemPrompt());

        // Ref outside the allowlist: FORBIDDEN.
        AiCubeRef other = new AiCubeRef("conn", "cat", "sch", "Different");
        AiAskService.AskOutcome forbidden = svc.askInSpace(
                "sales-analyst", other, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);
        assertTrue(forbidden.degraded());
        assertTrue(forbidden.reason().startsWith("FORBIDDEN"));
    }

    @Test
    public void spaceScopedAskRejectsLlmQueryAgainstNonAllowlistedCube() throws Exception {
        // saiku#1453/#1463 — the input ref is allowlisted (passes the pre-LLM check), but the
        // provider's emitted QUERY names a DIFFERENT cube outside the allowlist (as a prompt
        // injection would coax it to). The executed cube must be re-validated: the outcome has to
        // degrade FORBIDDEN, not surface a query the streaming endpoint would then execute against
        // the foreign cube.
        String foreignCubeJson = "{"
                + "\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"HR\"},"
                + "\"measures\":[{\"name\":\"Salary\"}]"
                + "}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok(foreignCubeJson, "claude-x", 10, 5)));
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("spaces");
        java.nio.file.Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"}"
                        + "]}");
        svc.setSpaces(new AgentSpaceRegistry(tmp));

        // CUBE (Sales) is allowlisted, so the pre-LLM check passes; the model then emits an HR query.
        AiAskService.AskOutcome out =
                svc.askInSpace("sales-analyst", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);

        assertTrue("LLM-chosen cube outside the allowlist must be refused", out.degraded());
        assertTrue(
                "denial must carry the FORBIDDEN prefix so the 403 mapping applies: " + out.reason(),
                out.reason().startsWith("FORBIDDEN"));
        assertNull("a forbidden cross-cube query must not surface an executable request", out.request());
    }

    @Test
    public void spaceScopedAskAllowsLlmQueryAgainstAllowlistedCube() throws Exception {
        // Companion to the bypass test: when the emitted QUERY names an allowlisted cube, the
        // re-check is transparent — the request surfaces normally.
        String allowedCubeJson = "{"
                + "\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"},"
                + "\"measures\":[{\"name\":\"Store Sales\"}]"
                + "}";
        AiAskService svc = new AiAskService(
                fixedSchemaService(emptySchema()), stub(NlAskResponse.ok(allowedCubeJson, "claude-x", 10, 5)));
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("spaces");
        java.nio.file.Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"}"
                        + "]}");
        svc.setSpaces(new AgentSpaceRegistry(tmp));

        AiAskService.AskOutcome out =
                svc.askInSpace("sales-analyst", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);

        assertFalse(out.degraded());
        assertNotNull(out.request());
        assertEquals("Sales", out.request().getCube().getCubeName());
    }

    @Test
    public void spaceScopedAskUsesDefaultCubeWhenAbsent() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok(
                    "{\"cube\":{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"},"
                            + "\"measures\":[]}",
                    "m",
                    0,
                    0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("spaces");
        java.nio.file.Files.writeString(
                tmp.resolve("sales.json"),
                "{\"id\":\"sales-analyst\",\"name\":\"Sales Analyst\","
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"}"
                        + "]}");
        svc.setSpaces(new AgentSpaceRegistry(tmp));

        AiAskService.AskOutcome out =
                svc.askInSpace("sales-analyst", null, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);
        assertFalse(out.degraded());
        assertEquals("Sales", seen.get().cubeRef().getCubeName());
    }

    @Test
    public void spaceFiltersSkillCatalogueToAllowlist() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("skills");
        java.nio.file.Files.writeString(
                tmp.resolve("allowed.md"),
                "---\nname: allowed-skill\ndescription: Allowed skill for this space\n---\n\nbody\n");
        java.nio.file.Files.writeString(
                tmp.resolve("blocked.md"),
                "---\nname: blocked-skill\ndescription: Blocked skill for this space\n---\n\nbody\n");
        svc.setSkills(new AgentSkillRegistry(tmp));

        java.nio.file.Path spacesTmp = java.nio.file.Files.createTempDirectory("spaces");
        java.nio.file.Files.writeString(
                spacesTmp.resolve("sales.json"),
                "{\"id\":\"sales\",\"name\":\"Sales\","
                        + "\"skillAllowlist\":[\"allowed-skill\"],"
                        + "\"cubeAllowlist\":["
                        + "{\"connectionName\":\"conn\",\"catalog\":\"cat\",\"schema\":\"sch\",\"cubeName\":\"Sales\"}"
                        + "]}");
        svc.setSpaces(new AgentSpaceRegistry(spacesTmp));

        svc.askInSpace("sales", CUBE, "show sales", List.of(), null, NlAskRequest.ForceTool.AUTO, null);

        String fragment = seen.get().skillsFragment();
        assertNotNull(fragment);
        assertTrue("allowed skill should surface: " + fragment, fragment.contains("/allowed-skill"));
        assertFalse("blocked skill must not surface: " + fragment, fragment.contains("/blocked-skill"));
    }

    /* ---- LLM-egress gate: cellset-digest strip (#2) + PII-filtered schema (#3) ---- */

    @Test
    public void egressSchemaOnlyStripsCellsetDigestButAskStillSucceeds() {
        // Egress posture is the fail-closed default (schema-only) → the supplied cellset digest must
        // be withheld from the provider request, but the ask still runs (degraded/ungrounded, NOT
        // refused).
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        svc.setEgressGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));

        AiAskService.AskOutcome out = svc.ask(CUBE, "spot the trend", List.of(), "| Country | Sales |\n| CA | 42 |");

        assertFalse("schema-only egress must degrade to schema-only, not refuse the ask", out.degraded());
        assertNotNull(seen.get());
        assertNull(
                "cellset digest must be stripped under schema-only egress",
                seen.get().cellsetDigest());
    }

    @Test
    public void egressAggregatedPassesCellsetDigestThrough() {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);
        svc.setEgressGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));

        String digest = "| Country | Sales |\n| CA | 42 |";
        svc.ask(CUBE, "spot the trend", List.of(), digest);

        assertNotNull(seen.get());
        assertEquals(
                "aggregated egress must pass the digest through",
                digest,
                seen.get().cellsetDigest());
    }

    @Test
    public void egressGuardUnwiredFailsClosedAndStripsDigest() {
        // No setEgressGuard() call — the guard is null. Fail-closed: absence of a permit means the
        // digest is stripped, never leaked.
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), capturing);

        svc.ask(CUBE, "spot the trend", List.of(), "| Country | Sales |\n| CA | 42 |");

        assertNotNull(seen.get());
        assertNull(
                "an unwired egress guard must fail closed and strip the digest",
                seen.get().cellsetDigest());
    }

    @Test
    public void schemaHandedToProviderIsPiiFilteredAgentView() {
        // #3: the schema JSON in the provider request must be the agent view — a PII-tagged level's
        // sample members (and captions) must NOT appear; the [REDACTED] sentinel does.
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
        };
        AiAskService svc = new AiAskService(fixedSchemaService(piiSchema()), capturing);

        svc.ask(CUBE, "show customers", List.of());

        String schemaJson = seen.get().cubeSchemaJson();
        assertNotNull(schemaJson);
        assertFalse("PII sample member caption must not egress: " + schemaJson, schemaJson.contains("John Smith"));
        assertFalse("PII display name must not egress: " + schemaJson, schemaJson.contains("Customer Name"));
        assertTrue("redaction sentinel must be present: " + schemaJson, schemaJson.contains("[REDACTED]"));
    }

    /* ---- Task 5: chained-ask wiring (converter + setter-injected ThinQueryService) ---- */

    @Test
    public void converterIsAlwaysNonNull() {
        // Stateless, instantiated directly — no wiring required, unlike thinQueryService().
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        assertNotNull(svc.converter());
    }

    @Test
    public void thinQueryServiceIsNullUntilWiredThenReturnsTheSetInstance() {
        AiAskService svc = new AiAskService(fixedSchemaService(emptySchema()), stub(NlAskResponse.ok("{}", "m", 0, 0)));
        assertNull("unwired executor must be null, not silently defaulted", svc.thinQueryService());

        ThinQueryService executor = new ThinQueryService();
        svc.setThinQueryService(executor);

        assertEquals(executor, svc.thinQueryService());
    }

    // ---------- helpers ----------

    private static AiCubeMetadataService fixedSchemaService(AiSchema schema) {
        return ref -> schema;
    }

    private static AiSchema emptySchema() {
        return new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
    }

    /** Schema carrying a PII-tagged level with sensitive sample members, to prove #3 filtering. */
    private static AiSchema piiSchema() {
        AiSchema schema = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
        AiSchema.Level pii = new AiSchema.Level("CustomerName", "[Customers].[CustomerName]");
        pii.displayName = "Customer Name";
        pii.pii = true;
        pii.sampleMembers = List.of(
                new AiSchema.MemberSample("John Smith", "[Customers].[USA].[CA].[John Smith]"),
                new AiSchema.MemberSample("Jane Doe", "[Customers].[USA].[CA].[Jane Doe]"));
        AiSchema.Dimension d = new AiSchema.Dimension("Customers", "[Customers]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customers", "[Customers]");
        h.levels.put(AiSchema.key("CustomerName"), pii);
        d.hierarchies.put(AiSchema.key("Customers"), h);
        schema.dimensions.put(AiSchema.key("Customers"), d);
        return schema;
    }

    private static NlAskProvider stub(NlAskResponse fixed) {
        return req -> fixed;
    }
}
