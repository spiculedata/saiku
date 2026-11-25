/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources.share;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.web.rest.resources.notebooks.Notebook;
import org.saiku.web.rest.resources.notebooks.NotebookCell;
import org.saiku.web.security.share.ShareTokenAuthFilter.ShareGuestDetails;
import org.saiku.web.service.SessionService;
import org.saiku.web.share.ShareToken;
import org.saiku.web.share.ShareTokenStore;

/** Unit tests for {@link NotebookShareViewResource}. */
public class NotebookShareViewResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotebookShareViewResource resource;
    private StubDatasources datasources;
    private StubQuery2 query2;
    private ShareTokenStore tokenStore;
    private Notebook notebook;

    @Before
    public void setUp() {
        resource = new NotebookShareViewResource();
        datasources = new StubDatasources();
        query2 = new StubQuery2();
        resource.setDatasourceService(datasources);
        resource.setSessionService(new StubSessionService());
        resource.setQuery2Resource(query2);
        resource.setOlapDiscoverService(new StubDiscover());
        tokenStore = new ShareTokenStore(null);
        resource.setShareTokenStore(tokenStore);
        notebook = new Notebook();
        notebook.cells = new java.util.ArrayList<>();
    }

    @After
    public void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static final class StubDatasources extends DatasourceService {
        String fileData;

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            if ("gone.saikunb".equals(path)) {
                throw new IllegalStateException("deleted");
            }
            return fileData;
        }
    }

    private static final class StubSessionService extends SessionService {
        @Override
        public <T> T runAs(String username, List<String> roles, java.util.function.Supplier<T> action) {
            return action.get();
        }
    }

    private static final class StubDiscover extends OlapDiscoverService {
        @Override
        public List<SaikuCube> getAllCubes() {
            return List.of(new SaikuCube("conn", "[Sales]", "Sales", "Sales", "cat", "sch"));
        }
    }

    private static final class StubQuery2 extends org.saiku.web.rest.resources.Query2Resource {
        String executedMdx;

        @Override
        public Response execute(org.saiku.olap.query2.ThinQuery tq, jakarta.ws.rs.core.HttpHeaders headers) {
            executedMdx = tq.getMdx();
            return Response.ok(Map.of("status", "OK")).build();
        }
    }

    private void asGuest(String notebookPath) {
        ShareToken token = tokenStore.create(notebookPath, "owner", List.of("ROLE_USER"), 3600_000L, null);
        org.springframework.security.authentication.TestingAuthenticationToken auth =
                new org.springframework.security.authentication.TestingAuthenticationToken("guest", token.token);
        auth.setDetails(new ShareGuestDetails(token.token, notebookPath, "owner", List.of("ROLE_USER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
    }

    private void storeNotebook() throws Exception {
        datasources.fileData = MAPPER.writeValueAsString(notebook);
    }

    private NotebookCell mdxCell(String id) {
        NotebookCell cell = new NotebookCell();
        cell.id = id;
        cell.type = "mdx";
        cell.mdx = "SELECT FROM [Sales]";
        org.saiku.service.olap.ai.AiCubeRef ref = new org.saiku.service.olap.ai.AiCubeRef();
        ref.setConnectionName("conn");
        ref.setCatalog("cat");
        ref.setSchema("sch");
        ref.setCubeName("Sales");
        cell.cube = ref;
        return cell;
    }

    @Test
    public void without_a_guest_everything_is_invalid() {
        assertEquals(401, resource.notebook().getStatus());
        assertEquals(401, resource.cellQuery("c1").getStatus());
    }

    @Test
    public void notebook_serves_the_pinned_notebook() throws Exception {
        asGuest("n1.saikunb");
        storeNotebook();
        Response response = resource.notebook();
        assertEquals(200, response.getStatus());
    }

    @Test
    public void notebook_404s_when_the_file_is_gone() {
        asGuest("gone.saikunb");
        assertEquals(404, resource.notebook().getStatus());
    }

    @Test
    public void notebook_404s_when_the_path_is_not_a_notebook() {
        asGuest("other.saikudash");
        assertEquals(404, resource.notebook().getStatus());
    }

    @Test
    public void notebook_404s_when_the_file_is_unparseable() {
        asGuest("n1.saikunb");
        datasources.fileData = "not json";
        assertEquals(404, resource.notebook().getStatus());
    }

    @Test
    public void cell_query_runs_the_mdx_under_the_owner_scope() throws Exception {
        notebook.cells.add(mdxCell("c1"));
        storeNotebook();
        asGuest("n1.saikunb");

        Response response = resource.cellQuery("c1");

        assertEquals(200, response.getStatus());
        assertEquals("SELECT FROM [Sales]", query2.executedMdx);
    }

    @Test
    public void cell_query_rejects_an_unknown_cell() throws Exception {
        storeNotebook();
        asGuest("n1.saikunb");
        assertEquals(404, resource.cellQuery("missing").getStatus());
    }

    @Test
    public void cell_query_rejects_a_markdown_cell() throws Exception {
        NotebookCell cell = new NotebookCell();
        cell.id = "c2";
        cell.type = "markdown";
        notebook.cells.add(cell);
        storeNotebook();
        asGuest("n1.saikunb");
        assertEquals(404, resource.cellQuery("c2").getStatus());
    }

    @Test
    public void cell_query_404s_when_the_cell_cube_is_no_longer_available() throws Exception {
        NotebookCell cell = mdxCell("c1");
        cell.cube.setCubeName("Vanished");
        notebook.cells.add(cell);
        storeNotebook();
        asGuest("n1.saikunb");

        Response response = resource.cellQuery("c1");

        assertEquals(404, response.getStatus());
    }

    @Test
    public void cell_query_404s_for_an_unknown_cell_on_an_empty_notebook() throws Exception {
        // "{}" deserialises to a Notebook whose cells default is non-null but
        // empty, so the cell lookup misses and the endpoint 404s.
        datasources.fileData = "{}";
        asGuest("n1.saikunb");
        assertEquals(404, resource.cellQuery("c1").getStatus());
    }
}
