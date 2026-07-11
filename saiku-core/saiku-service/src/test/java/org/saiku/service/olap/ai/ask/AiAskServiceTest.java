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
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
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
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
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
    public void spaceScopedAskUsesDefaultCubeWhenAbsent() throws Exception {
        AtomicReference<NlAskRequest> seen = new AtomicReference<>();
        NlAskProvider capturing = req -> {
            seen.set(req);
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 0, 0);
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

    // ---------- helpers ----------

    private static AiCubeMetadataService fixedSchemaService(AiSchema schema) {
        return ref -> schema;
    }

    private static AiSchema emptySchema() {
        return new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
    }

    private static NlAskProvider stub(NlAskResponse fixed) {
        return req -> fixed;
    }
}
