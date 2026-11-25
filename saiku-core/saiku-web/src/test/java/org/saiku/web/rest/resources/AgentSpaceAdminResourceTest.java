/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.olap.ai.ask.AgentSpaceRegistry;

/** Unit tests for {@link AgentSpaceAdminResource}. */
public class AgentSpaceAdminResourceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AgentSpaceAdminResource resource;
    private AgentSpaceRegistry registry;

    @Before
    public void setUp() throws Exception {
        registry = new AgentSpaceRegistry(
                Files.createTempDirectory(folder.getRoot().toPath(), "agents").toString());
        resource = new AgentSpaceAdminResource();
        resource.setRegistry(registry);
    }

    private static AgentSpaceAdminResource.SpaceDto space(String id, String name) {
        AgentSpaceAdminResource.SpaceDto dto = new AgentSpaceAdminResource.SpaceDto();
        dto.id = id;
        dto.name = name;
        dto.description = "desc";
        dto.systemPrompt = "You are a data agent.";
        dto.skillAllowlist = List.of("sql");
        dto.suggestedPrompts = List.of("Top sellers?");
        AgentSpaceAdminResource.CubeRefDto cube = new AgentSpaceAdminResource.CubeRefDto();
        cube.connectionName = "conn";
        cube.catalog = "cat";
        cube.schema = "sch";
        cube.cubeName = "cube";
        dto.cubeAllowlist = List.of(cube);
        return dto;
    }

    @Test
    public void save_then_list_round_trips_a_space() {
        Response saved = resource.save("analyst-helper", space("analyst-helper", "Analyst Helper"));
        assertEquals(200, saved.getStatus());

        List<AgentSpaceAdminResource.SpaceDto> spaces = resource.list();
        assertEquals(1, spaces.size());
        assertEquals("Analyst Helper", spaces.get(0).name);
        assertEquals("analyst-helper", spaces.get(0).id);
        assertEquals(1, spaces.get(0).cubeAllowlist.size());
        assertEquals("conn", spaces.get(0).cubeAllowlist.get(0).connectionName);
        assertEquals(1, spaces.get(0).skillAllowlist.size());
    }

    @Test
    public void save_rejects_a_non_kebab_case_id() {
        Response response = resource.save("Bad Id", space("Bad Id", "n"));
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("kebab-case"));
    }

    @Test
    public void save_requires_a_name() {
        AgentSpaceAdminResource.SpaceDto noName = new AgentSpaceAdminResource.SpaceDto();
        noName.id = "x";
        noName.name = " ";
        assertEquals(400, resource.save("x", noName).getStatus());
        assertEquals(400, resource.save("x", null).getStatus());
    }

    @Test
    public void save_rejects_an_incomplete_cube_reference() {
        AgentSpaceAdminResource.SpaceDto dto = space("x", "n");
        AgentSpaceAdminResource.CubeRefDto bad = new AgentSpaceAdminResource.CubeRefDto();
        bad.connectionName = "conn";
        dto.cubeAllowlist = List.of(bad);
        assertEquals(400, resource.save("x", dto).getStatus());
    }

    @Test
    public void save_accepts_null_skill_and_prompt_lists() {
        // cubeAllowlist must be non-empty (the registry refuses empty spaces),
        // but the other lists may be omitted entirely.
        AgentSpaceAdminResource.SpaceDto dto = new AgentSpaceAdminResource.SpaceDto();
        dto.id = "minimal";
        dto.name = "Minimal";
        AgentSpaceAdminResource.CubeRefDto cube = new AgentSpaceAdminResource.CubeRefDto();
        cube.connectionName = "conn";
        cube.catalog = "cat";
        cube.schema = "sch";
        cube.cubeName = "cube";
        dto.cubeAllowlist = List.of(cube);
        Response response = resource.save("minimal", dto);
        assertEquals(200, response.getStatus());
        AgentSpaceAdminResource.SpaceDto listed = resource.list().get(0);
        assertNotNull(listed);
        assertTrue(listed.skillAllowlist.isEmpty());
        assertTrue(listed.suggestedPrompts.isEmpty());
    }

    @Test
    public void delete_removes_a_saved_space_and_404s_the_second_time() {
        resource.save("gone", space("gone", "Gone"));
        assertEquals(200, resource.delete("gone").getStatus());
        Response second = resource.delete("gone");
        assertEquals(404, second.getStatus());
        assertTrue(resource.list().isEmpty());
    }

    @Test
    public void errors_lists_unparseable_files() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(
                Files.createTempDirectory(folder.getRoot().toPath(), "broken").toString());
        Files.write(root.resolve("bad json.json"), "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AgentSpaceAdminResource broken = new AgentSpaceAdminResource();
        broken.setRegistry(new AgentSpaceRegistry(root.toString()));
        List<Map<String, String>> errors = broken.errors();
        assertEquals(1, errors.size());
        assertEquals("bad json.json", errors.get(0).get("source"));
    }

    @Test
    public void a_round_tripped_space_maps_every_cube_field() {
        resource.save("cubes", space("cubes", "Cubes"));
        AgentSpaceAdminResource.SpaceDto dto = resource.list().get(0);
        assertEquals("cat", dto.cubeAllowlist.get(0).catalog);
        assertEquals("sch", dto.cubeAllowlist.get(0).schema);
        assertEquals("cube", dto.cubeAllowlist.get(0).cubeName);
    }
}
