/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AgentSkillRegistry;
import org.saiku.service.olap.ai.ask.AgentSpaceRegistry;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * saiku#2542: the AI catalogue's operator surfaces — detail records, {@code ?errors=true}
 * diagnostics and the three force-refresh endpoints — are admin-only. The open list endpoints
 * (summaries only) stay reachable for every authenticated caller.
 */
public class AiQueryResourceCatalogueAdminTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AiQueryResource resource;

    @Before
    public void setUp() throws Exception {
        login("ROLE_ADMIN");
        resource = new AiQueryResource();
        AiSchema schema = new AiSchema("c/c/c/Sales", "Sales", "[Sales]");
        NlAskProvider provider = req -> {
            throw new IllegalStateException("not needed for these endpoints");
        };
        AiAskService svc = new AiAskService(ref -> schema, provider);

        Path skills = tmp.newFolder("skills").toPath();
        Files.write(
                skills.resolve("ok.md"),
                ("---\nname: weekly-rollup\ndescription: Weekly revenue rollup\n"
                                + "cube: unknown_foodmart/FoodMart/FoodMart/Sales\n---\n\nbody\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(skills.resolve("bad.md"), "no frontmatter here\n".getBytes(StandardCharsets.UTF_8));
        svc.setSkills(new AgentSkillRegistry(skills));

        Path spaces = tmp.newFolder("spaces").toPath();
        Files.write(
                spaces.resolve("ok.json"),
                ("{\"id\":\"analyst\",\"name\":\"Analyst\",\"description\":\"d\","
                                + "\"systemPrompt\":\"You are the Analyst.\","
                                + "\"cubeAllowlist\":[{\"connectionName\":\"c\",\"catalog\":\"cat\",\"schema\":\"s\",\"cubeName\":\"Sales\"}],"
                                + "\"skillAllowlist\":[],\"suggestedPrompts\":[]}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(spaces.resolve("bad.json"), "{".getBytes(StandardCharsets.UTF_8));
        svc.setSpaces(new AgentSpaceRegistry(spaces));

        resource.setAskService(svc);
    }

    @After
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String role) {
        org.springframework.security.core.context.SecurityContext ctx =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "u", "x", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
        SecurityContextHolder.setContext(ctx);
    }

    /* ------------------------------- skills ------------------------------- */

    @Test
    public void nonAdminIsRefusedOnSkillDetail() {
        login("ROLE_USER");
        assertEquals(403, resource.getSkill("weekly-rollup").getStatus());
    }

    @Test
    public void adminGetsTheSkillBody() {
        Response resp = resource.getSkill("weekly-rollup");
        assertEquals(200, resp.getStatus());
        JsonNode body = MAPPER.valueToTree(resp.getEntity());
        assertEquals("weekly-rollup", body.path("name").asText());
    }

    @Test
    public void nonAdminIsRefusedOnSkillErrorsAndRefresh() {
        login("ROLE_USER");
        assertEquals(403, resource.listSkills(true).getStatus());
        assertEquals(403, resource.refreshSkills().getStatus());
        assertEquals(
                "the summary list stays open", 200, resource.listSkills(false).getStatus());
    }

    @Test
    public void adminSeesTheParseErrors() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) resource.listSkills(true).getEntity();
        assertEquals(1, ((List<?>) body.get("errors")).size());
    }

    /* ------------------------------- spaces ------------------------------- */

    @Test
    public void nonAdminIsRefusedOnSpaceErrorsAndRefresh() {
        login("ROLE_USER");
        assertEquals(403, resource.listSpaces(true).getStatus());
        assertEquals(403, resource.refreshSpaces().getStatus());
        assertEquals(
                "the summary list stays open", 200, resource.listSpaces(false).getStatus());
    }

    @Test
    public void adminSeesTheSpaceParseErrors() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) resource.listSpaces(true).getEntity();
        assertEquals(1, ((List<?>) body.get("errors")).size());
    }

    /* -------------------------- certified (list) -------------------------- */

    @Test
    public void nonAdminIsRefusedOnCertifiedErrorsAndRefresh() {
        login("ROLE_USER");
        assertEquals(403, resource.listCertified(true).getStatus());
        assertEquals(403, resource.refreshCertified().getStatus());
        assertEquals(
                "the summary list stays open",
                200,
                resource.listCertified(false).getStatus());
    }
}
