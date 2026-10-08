/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;

/**
 * saiku#1430 — the ask layer's certified routing.
 *
 * <p>The acceptance criterion is behavioural: when an ask's intent matches a certified query, the
 * answer is the certified one. These tests pin the parts of that which are easy to regress silently
 * — that the provider is NOT consulted for a certified match (a prompt-level "prefer this" would
 * pass a weaker test), and that the narrow conditions which must NOT short-circuit really don't.
 */
public class AiAskServiceCertifiedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final AiCubeRef CUBE = new AiCubeRef("conn", "cat", "sch", "Sales");

    private AtomicInteger providerCalls;
    private CertifiedQueryRegistry registry;
    private Path root;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("certified").toPath();
        Files.write(
                root.resolve("monthly-net-revenue.json"),
                entry("monthly-net-revenue", "\"monthly revenue\",\"revenue by month\",\"monthly net revenue\"")
                        .getBytes(StandardCharsets.UTF_8));
        registry = new CertifiedQueryRegistry(root);
        providerCalls = new AtomicInteger();
    }

    private static String entry(String id, String intents) {
        return "{\"id\":\"" + id + "\",\"description\":\"d\",\"matchIntent\":[" + intents + "],\"query\":"
                + "{\"name\":\"q\",\"cube\":{\"name\":\"Sales\",\"connection\":\"conn\"},"
                + "\"type\":\"MDX\",\"mdx\":\"SELECT {} ON COLUMNS FROM [Sales]\"}}";
    }

    private AiAskService service() {
        AiAskService svc = new AiAskService(ref -> emptySchema(), req -> {
            providerCalls.incrementAndGet();
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "claude-x", 1, 1);
        });
        svc.setCertifiedQueries(registry);
        return svc;
    }

    private static AiSchema emptySchema() {
        return new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");
    }

    @Test
    public void matchingIntentShortCircuitsToTheCertifiedQuery() {
        AiAskService.AskOutcome out = service().ask(CUBE, "what was monthly revenue?", List.of());
        assertFalse(out.degraded());
        assertEquals(AiAskService.AskOutcome.Kind.CERTIFIED, out.kind());
        assertNotNull(out.certifiedQuery());
        assertEquals("monthly-net-revenue", out.certifiedQuery().id());
        // The model was never asked — a re-derived query is not a fallback here, it's a contradiction.
        assertEquals(0, providerCalls.get());
        assertNull(out.request());
        assertNull(out.model());
    }

    @Test
    public void nonMatchingAskReachesTheModelUnchanged() {
        AiAskService.AskOutcome out = service().ask(CUBE, "how many units did we ship?", List.of());
        assertFalse(out.degraded());
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertNull(out.certifiedQuery());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void followUpAboutVisibleDataIsNotShortCircuited() {
        // A digest on screen means "tell me about THIS", not "go fetch data" — substituting a
        // certified query here would answer a question nobody asked.
        AiAskService.AskOutcome out = service().ask(CUBE, "why did monthly revenue drop?", List.of(), "| A | 1 |");
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void explicitNonQueryIntentIsNotShortCircuited() {
        AiAskService.AskOutcome out =
                service().ask(CUBE, "monthly revenue", List.of(), null, NlAskRequest.ForceTool.INSIGHT, null);
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void slashCommandOutranksACertifiedMatch() {
        AiAskService.AskOutcome out = service().ask(CUBE, "/monthly-rollup revenue", List.of());
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void askAgainstAnotherCubeIsNotShortCircuited() {
        AiAskService.AskOutcome out =
                service().ask(new AiCubeRef("conn", "cat", "sch", "Warehouse"), "what was monthly revenue?", List.of());
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void unwiredRegistryLeavesTheClassicPathIntact() {
        AiAskService svc = new AiAskService(ref -> emptySchema(), req -> {
            providerCalls.incrementAndGet();
            return NlAskResponse.ok("{\"cube\":{\"cubeName\":\"Sales\"},\"measures\":[]}", "m", 1, 1);
        });
        assertNull(svc.certifiedQueries());
        AiAskService.AskOutcome out = svc.ask(CUBE, "what was monthly revenue?", List.of());
        assertEquals(AiAskService.AskOutcome.Kind.QUERY, out.kind());
        assertEquals(1, providerCalls.get());
    }

    @Test
    public void cataloguedFileIsPickedUpWithoutAReload() throws Exception {
        AiAskService svc = service();
        assertEquals(
                AiAskService.AskOutcome.Kind.QUERY,
                svc.ask(CUBE, "quarterly churn", List.of()).kind());
        Files.write(
                root.resolve("quarterly-churn.json"),
                entry("quarterly-churn", "\"quarterly churn\"").getBytes(StandardCharsets.UTF_8));
        AiAskService.AskOutcome out = svc.ask(CUBE, "quarterly churn please", List.of());
        assertEquals(AiAskService.AskOutcome.Kind.CERTIFIED, out.kind());
        assertEquals("quarterly-churn", out.certifiedQuery().id());
    }

    @Test
    public void certifiedQueryIsTheApprovalArtefactItself() {
        AiAskService.AskOutcome out = service().ask(CUBE, "monthly net revenue", List.of());
        assertEquals(AiAskService.AskOutcome.Kind.CERTIFIED, out.kind());
        // Verbatim: the ThinQuery handed back is the one parsed off disk, unconverted, unrewritten.
        assertEquals(
                "SELECT {} ON COLUMNS FROM [Sales]",
                out.certifiedQuery().query().getMdx());
        assertEquals("conn", out.certifiedQuery().query().getCube().getConnection());
    }

    @Test
    public void spaceScopedAskAlsoPrefersCertified() throws Exception {
        // The two features compose: a persona still scopes the cube, and a certified answer for
        // that cube still wins over re-derivation.
        Path spacesRoot = tmp.newFolder("agent-spaces").toPath();
        Files.write(
                spacesRoot.resolve("finance.json"),
                ("{\"id\":\"finance\",\"name\":\"Finance\",\"systemPrompt\":\"You are finance.\","
                                + "\"cubeAllowlist\":[{\"connectionName\":\"conn\",\"catalog\":\"cat\","
                                + "\"schema\":\"sch\",\"cubeName\":\"Sales\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
        AiAskService svc = service();
        svc.setSpaces(new AgentSpaceRegistry(spacesRoot));

        AiAskService.AskOutcome out =
                svc.askInSpace("finance", CUBE, "what was monthly revenue?", List.of(), null, null, null);
        assertFalse(out.degraded());
        assertEquals(AiAskService.AskOutcome.Kind.CERTIFIED, out.kind());
        assertEquals("monthly-net-revenue", out.certifiedQuery().id());
        assertEquals(0, providerCalls.get());
    }
}
