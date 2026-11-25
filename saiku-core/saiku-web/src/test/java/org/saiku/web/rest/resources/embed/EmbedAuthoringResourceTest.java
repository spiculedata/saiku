/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.embed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;
import org.saiku.web.rest.resources.AiQueryResource;
import org.saiku.web.security.embed.EmbedAuthFilter;
import org.saiku.web.security.embed.EmbedAuthFilter.EmbedGuestDetails;
import org.saiku.web.service.SessionService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Resource-level coverage of {@link EmbedAuthoringResource} — the Creator Mode
 * write surface (saiku#1435). Verifies the re-asserted pin, the
 * tenant-derived scope, the validated query acceptance, the owner-scoped
 * {@code runAs} execution and the hardened response headers, using hand-rolled
 * stubs in the style of {@code EmbedViewResourceTest}.
 */
public class EmbedAuthoringResourceTest {

    private static final String PINNED = "/foodmart/foodmart/foodmart/Sales";
    private static final String PINNED_NO_SLASH = "foodmart/foodmart/foodmart/Sales";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StubDatasourceService ds;
    private StubSessionService session;
    private StubThinQueryService tqs;
    private StubAiQueryResource ai;
    private StubAuditLog audit;
    private StubDiscoverService discover;
    private EmbedAuthoringResource resource;

    @Before
    public void setUp() {
        ds = new StubDatasourceService();
        session = new StubSessionService();
        tqs = new StubThinQueryService();
        ai = new StubAiQueryResource();
        audit = new StubAuditLog();
        discover = new StubDiscoverService();
        resource = new EmbedAuthoringResource();
        resource.setDatasourceService(ds);
        resource.setSessionService(session);
        resource.setThinQueryService(tqs);
        resource.setAiQueryResource(ai);
        resource.setAuditLog(audit);
        resource.setOlapDiscoverService(discover);
        pinGuest(PINNED, "tenant-1");
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ---------------------------- fixtures ---------------------------- */

    private static void pin(String kind, String path, String tenant) {
        EmbedGuestDetails details = new EmbedGuestDetails(
                "token-xyz",
                kind,
                path,
                "admin",
                List.of("ROLE_ADMIN"),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT,
                "sub-123",
                null,
                tenant);
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void pinGuest(String path, String tenant) {
        pin(EmbedAuthFilter.AUTHORING_KIND, path, tenant);
    }

    private static JsonNode validQueryDoc() {
        return MAPPER.valueToTree(validQuery());
    }

    private static ThinQuery validQuery() {
        ThinQuery tq = new ThinQuery("creator-query", null);
        tq.setType(ThinQuery.Type.QUERYMODEL);
        tq.setQueryType("OLAP");

        org.saiku.olap.query2.ThinSelection sel = new org.saiku.olap.query2.ThinSelection();
        org.saiku.olap.query2.ThinMember tm = new org.saiku.olap.query2.ThinMember();
        tm.setUniqueName("[Store].[USA]");
        sel.getMembers().add(tm);
        org.saiku.olap.query2.ThinLevel storeLevel =
                new org.saiku.olap.query2.ThinLevel("[Store].[Store]", "Store", sel, new ArrayList<>());
        Map<String, org.saiku.olap.query2.ThinLevel> storeLevels = new HashMap<>();
        storeLevels.put("[Store].[Store]", storeLevel);
        org.saiku.olap.query2.ThinHierarchy storeHierarchy =
                new org.saiku.olap.query2.ThinHierarchy("[Store]", "Store", "[Store]", storeLevels);

        Map<String, org.saiku.olap.query2.ThinLevel> measureLevels = new HashMap<>();
        measureLevels.put(
                "[Measures].[Unit Sales]",
                new org.saiku.olap.query2.ThinLevel("[Measures].[Unit Sales]", "Unit Sales", null, new ArrayList<>()));
        org.saiku.olap.query2.ThinHierarchy measureHierarchy =
                new org.saiku.olap.query2.ThinHierarchy("[Measures]", "Measures", "[Measures]", measureLevels);

        org.olap4j.impl.NamedListImpl<org.saiku.olap.query2.ThinHierarchy> rows = new org.olap4j.impl.NamedListImpl<>();
        rows.add(storeHierarchy);
        org.olap4j.impl.NamedListImpl<org.saiku.olap.query2.ThinHierarchy> cols = new org.olap4j.impl.NamedListImpl<>();
        cols.add(measureHierarchy);

        Map<AxisLocation, org.saiku.olap.query2.ThinAxis> axes = new HashMap<>();
        axes.put(AxisLocation.ROWS, new org.saiku.olap.query2.ThinAxis(AxisLocation.ROWS, rows, true, null));
        axes.put(AxisLocation.COLUMNS, new org.saiku.olap.query2.ThinAxis(AxisLocation.COLUMNS, cols, true, null));

        org.saiku.olap.query2.ThinQueryModel model = new org.saiku.olap.query2.ThinQueryModel();
        model.setAxes(axes);
        model.setDetails(new org.saiku.olap.query2.ThinDetails(
                AxisLocation.COLUMNS,
                org.saiku.olap.query2.ThinDetails.Location.BOTTOM,
                new ArrayList<>(List.of(new org.saiku.olap.query2.ThinMeasure(
                        "[Measures].[Unit Sales]",
                        "[Measures].[Unit Sales]",
                        "[Measures].[Unit Sales]",
                        org.saiku.olap.query2.ThinMeasure.Type.EXACT)))));
        tq.setQueryModel(model);
        return tq;
    }

    /* ---------------------------- guard ---------------------------- */

    @Test
    public void context_with_no_guest_returns_401() {
        SecurityContextHolder.clearContext();
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(401, r.getStatus());
        assertHardened(r);
    }

    @Test
    public void context_refuses_a_non_authoring_guest() {
        pin("query", "/homes/admin/sales.saiku", "tenant-1");
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void context_refuses_an_invalid_tenant_id() {
        pin(EmbedAuthFilter.AUTHORING_KIND, PINNED, "../../etc");
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void context_refuses_a_cube_pin_mismatch() {
        // The resource re-derives the pin from the token; a request for a
        // different cube than the token pins must never answer.
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Warehouse");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void context_refuses_a_guest_without_an_owner() {
        EmbedGuestDetails details = new EmbedGuestDetails(
                "token-xyz",
                EmbedAuthFilter.AUTHORING_KIND,
                PINNED,
                null,
                List.of(),
                org.saiku.web.embed.EmbedToken.RedactionPolicy.TENANT_DEFAULT,
                null,
                null,
                "tenant-1");
        PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                "embed-guest", details, List.of(new SimpleGrantedAuthority("ROLE_EMBED_GUEST")));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(401, r.getStatus());
    }

    @Test
    public void context_returns_the_pinned_cube_catalogue() {
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(200, r.getStatus());
        assertHardened(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("tenant-1", body.get("tenantId"));
        assertEquals("/homes/admin/embed-guest-tenant-1", body.get("scopePath"));
        assertEquals("[foodmart].[foodmart].[foodmart].[Sales]", body.get("cube"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) body.get("dimensions");
        assertEquals(1, dims.size());
        assertEquals("[Store]", dims.get(0).get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels =
                (List<Map<String, Object>>) dims.get(0).get("levels");
        assertEquals("[Store].[Store]", levels.get(0).get("name"));
        assertEquals(List.of("[Store].[USA]"), levels.get(0).get("members"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> measures = (List<Map<String, Object>>) body.get("measures");
        assertEquals(1, measures.size());
        assertEquals("[Measures].[Unit Sales]", measures.get(0).get("name"));
    }

    @Test
    public void context_returns_404_when_the_pinned_cube_cannot_resolve() {
        discover.fail = true;
        Response r = resource.context("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(404, r.getStatus());
        assertHardened(r);
    }

    /* ---------------------------- preview ---------------------------- */

    private EmbedAuthoringResource.SaveQueryBody body(String name, JsonNode query) {
        EmbedAuthoringResource.SaveQueryBody b = new EmbedAuthoringResource.SaveQueryBody();
        b.name = name;
        b.query = query;
        return b;
    }

    @Test
    public void preview_refuses_a_missing_query() {
        assertEquals(
                400,
                resource.preview("foodmart", "foodmart", "foodmart", "Sales", null, body("n", null))
                        .getStatus());
        assertEquals(
                400,
                resource.preview("foodmart", "foodmart", "foodmart", "Sales", null, null)
                        .getStatus());
    }

    @Test
    public void preview_refuses_a_query_naming_a_hierarchy_outside_the_catalogue() {
        ThinQuery bad = validQuery();
        bad.getQueryModel().getAxis(AxisLocation.ROWS).getHierarchies().get(0).setName("[NotACube].[X]");
        Response r =
                resource.preview("foodmart", "foodmart", "foodmart", "Sales", null, body("n", MAPPER.valueToTree(bad)));
        assertEquals(400, r.getStatus());
    }

    @Test
    public void preview_refuses_a_malformed_query_document() {
        JsonNode bad = MAPPER.createObjectNode().put("nonsense", true);
        Response r = resource.preview("foodmart", "foodmart", "foodmart", "Sales", null, body("n", bad));
        assertEquals(400, r.getStatus());
    }

    @Test
    public void preview_runs_and_returns_the_response_envelope() {
        Response r =
                resource.preview("foodmart", "foodmart", "foodmart", "Sales", "matrix", body("n", validQueryDoc()));
        assertEquals(200, r.getStatus());
        assertHardened(r);
        // Ran under the owner's identity.
        assertEquals("admin", session.lastRunAsUser);
        assertEquals(List.of("ROLE_ADMIN"), session.lastRunAsRoles);
        // The server owns the identity of the query: cube from the token,
        // generated name, no client MDX.
        ThinQuery tq = tqs.lastCreated;
        assertNotNull(tq);
        assertEquals("Sales", tq.getCube().getName());
        assertTrue(tq.getName().startsWith("embed-creator-"));
        assertNull(tq.getMdx());
        assertEquals(ThinQuery.Type.QUERYMODEL, tq.getType());
        assertEquals("matrix", ai.lastFormat);
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, audit.last.outcome);
    }

    @Test
    public void preview_defaults_the_format_to_records() {
        resource.preview("foodmart", "foodmart", "foodmart", "Sales", "garbage", body("n", validQueryDoc()));
        assertEquals("records", ai.lastFormat);
    }

    @Test
    public void preview_reports_a_runtime_failure_as_a_400() {
        tqs.failExecute = true;
        Response r = resource.preview("foodmart", "foodmart", "foodmart", "Sales", null, body("n", validQueryDoc()));
        assertEquals(400, r.getStatus());
        assertEquals(AiAuditEntry.OUTCOME_ERROR, audit.last.outcome);
    }

    /* ------------------------- saved query ------------------------- */

    @Test
    public void saveQuery_refuses_a_missing_name() {
        assertEquals(
                400,
                resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body(null, validQueryDoc()))
                        .getStatus());
        assertEquals(
                400,
                resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body("   ", validQueryDoc()))
                        .getStatus());
    }

    @Test
    public void saveQuery_refuses_a_missing_query() {
        assertEquals(
                400,
                resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body("Sales q", null))
                        .getStatus());
    }

    @Test
    public void saveQuery_writes_into_the_tenant_scope_folder() {
        EmbedAuthoringResource.SaveQueryBody b = body("My Sales Query", validQueryDoc());
        Response r = resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", b);
        assertEquals(200, r.getStatus());
        assertHardened(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) r.getEntity();
        assertEquals("OK", saved.get("status"));
        assertEquals("/homes/admin/embed-guest-tenant-1/My Sales Query.saiku", saved.get("path"));
        assertEquals("query", saved.get("type"));
        assertEquals("/homes/admin/embed-guest-tenant-1/My Sales Query.saiku", ds.lastSavePath);
        assertEquals("admin", ds.lastSaveUser);
        // The file is the executed, re-serialised ThinQuery.
        assertTrue(ds.lastSaveContent.contains("queryModel"));
    }

    @Test
    public void saveQuery_refuses_an_unusable_object_name() {
        assertEquals(
                400,
                resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body("///", validQueryDoc()))
                        .getStatus());
    }

    @Test
    public void saveQuery_reports_a_runtime_failure_as_a_400() {
        tqs.failExecute = true;
        Response r = resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body("q", validQueryDoc()));
        assertEquals(400, r.getStatus());
        assertEquals(AiAuditEntry.OUTCOME_ERROR, audit.last.outcome);
    }

    @Test
    public void saveQuery_reports_a_rejected_save_as_a_500() {
        ds.saveResponse = "Nope";
        Response r = resource.saveQuery("foodmart", "foodmart", "foodmart", "Sales", body("q", validQueryDoc()));
        assertEquals(500, r.getStatus());
    }

    /* -------------------------- dashboard -------------------------- */

    private EmbedAuthoringResource.SaveDashboardBody dashBody(String name, JsonNode dash) {
        EmbedAuthoringResource.SaveDashboardBody b = new EmbedAuthoringResource.SaveDashboardBody();
        b.name = name;
        b.dashboard = dash;
        return b;
    }

    @Test
    public void saveDashboard_refuses_a_missing_name_or_document() {
        assertEquals(
                400,
                resource.saveDashboard(
                                "foodmart", "foodmart", "foodmart", "Sales", dashBody(null, MAPPER.createObjectNode()))
                        .getStatus());
        assertEquals(
                400,
                resource.saveDashboard("foodmart", "foodmart", "foodmart", "Sales", dashBody("d", null))
                        .getStatus());
        assertEquals(
                400,
                resource.saveDashboard(
                                "foodmart",
                                "foodmart",
                                "foodmart",
                                "Sales",
                                dashBody("d", MAPPER.valueToTree(List.of("not-an-object"))))
                        .getStatus());
    }

    @Test
    public void saveDashboard_refuses_a_dashboard_without_a_layout() {
        com.fasterxml.jackson.databind.node.ObjectNode dash = MAPPER.createObjectNode();
        assertEquals(
                400,
                resource.saveDashboard("foodmart", "foodmart", "foodmart", "Sales", dashBody("d", dash))
                        .getStatus());
        dash.put("layout", "not-an-object");
        assertEquals(
                400,
                resource.saveDashboard("foodmart", "foodmart", "foodmart", "Sales", dashBody("d", dash))
                        .getStatus());
    }

    @Test
    public void saveDashboard_stamps_the_pinned_cube_into_the_document() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode dash = MAPPER.createObjectNode();
        dash.putObject("layout").put("rows", 2);
        Response r = resource.saveDashboard("foodmart", "foodmart", "foodmart", "Sales", dashBody("Q3 Board", dash));
        assertEquals(200, r.getStatus());
        assertHardened(r);
        assertEquals("/homes/admin/embed-guest-tenant-1/Q3 Board.saikudash", ds.lastSavePath);
        JsonNode stored = MAPPER.readTree(ds.lastSaveContent);
        assertEquals("tenant-1", stored.get("embedCreator").get("tenant").asText());
        assertEquals(PINNED_NO_SLASH, stored.get("embedCreator").get("cube").asText());
        assertEquals("sub-123", stored.get("embedCreator").get("sub").asText());
        assertEquals("dashboard", ((Map<?, ?>) r.getEntity()).get("type"));
    }

    /* ---------------------------- objects ---------------------------- */

    private static RepositoryFileObject file(String name, String owner, long modified) {
        return new RepositoryFileObject(
                name, null, "saiku", "/homes/admin/embed-guest-tenant-1/" + name, null, owner, modified);
    }

    @Test
    public void objects_lists_only_the_tenant_folder_and_only_creator_objects() {
        ds.files = new ArrayList<>();
        ds.files.add(file("a.saiku", "admin", 1L));
        ds.files.add(file("b.saikudash", null, 2L));
        ds.files.add(file("notes.txt", "admin", 3L));

        Response r = resource.objects("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(200, r.getStatus());
        assertHardened(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("tenant-1", body.get("tenantId"));
        assertEquals("/homes/admin/embed-guest-tenant-1", body.get("scopePath"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) body.get("objects");
        assertEquals(2, objects.size());
        assertEquals("a.saiku", objects.get(0).get("name"));
        assertEquals("query", objects.get(0).get("type"));
        assertEquals("/homes/admin/embed-guest-tenant-1/a.saiku", objects.get(0).get("path"));
        assertEquals("admin", objects.get(0).get("owner"));
        assertEquals("dashboard", objects.get(1).get("type"));
        assertEquals("", objects.get(1).get("owner"));
    }

    @Test
    public void objects_reports_a_listing_failure_as_a_500() {
        ds.filesFail = true;
        Response r = resource.objects("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(500, r.getStatus());
        assertHardened(r);
    }

    @Test
    public void objects_tolerates_a_null_listing() {
        ds.files = null;
        Response r = resource.objects("foodmart", "foodmart", "foodmart", "Sales");
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects =
                (List<Map<String, Object>>) ((Map<String, Object>) r.getEntity()).get("objects");
        assertTrue(objects.isEmpty());
    }

    /* ------------------------- load / delete ------------------------- */

    @Test
    public void loadObject_refuses_an_unusable_name() {
        assertEquals(
                400,
                resource.loadObject("foodmart", "foodmart", "foodmart", "Sales", null)
                        .getStatus());
        assertEquals(
                400,
                resource.loadObject("foodmart", "foodmart", "foodmart", "Sales", "  ")
                        .getStatus());
        assertEquals(
                400,
                resource.loadObject("foodmart", "foodmart", "foodmart", "Sales", "///")
                        .getStatus());
    }

    @Test
    public void loadObject_returns_the_stored_document() {
        ds.fileData = "{\"name\":\"q\"}";
        Response r = resource.loadObject("foodmart", "foodmart", "foodmart", "Sales", "q.saiku");
        assertEquals(200, r.getStatus());
        assertHardened(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("q.saiku", body.get("name"));
        assertEquals("/homes/admin/embed-guest-tenant-1/q.saiku", body.get("path"));
        assertEquals("{\"name\":\"q\"}", body.get("content"));
    }

    @Test
    public void loadObject_returns_404_for_blank_content() {
        ds.fileData = "  ";
        Response r = resource.loadObject("foodmart", "foodmart", "foodmart", "Sales", "q.saiku");
        assertEquals(404, r.getStatus());
    }

    @Test
    public void deleteObject_removes_the_object_inside_the_scope() {
        Response r = resource.deleteObject("foodmart", "foodmart", "foodmart", "Sales", "q.saiku");
        assertEquals(200, r.getStatus());
        assertHardened(r);
        assertEquals("/homes/admin/embed-guest-tenant-1/q.saiku", ds.lastRemovePath);
        assertEquals(AiAuditEntry.OUTCOME_SUCCESS, audit.last.outcome);
    }

    @Test
    public void deleteObject_reports_a_rejected_delete_as_a_500() {
        ds.removeResponse = "Remove Rejected";
        Response r = resource.deleteObject("foodmart", "foodmart", "foodmart", "Sales", "q.saiku");
        assertEquals(500, r.getStatus());
        assertEquals(AiAuditEntry.OUTCOME_ERROR, audit.last.outcome);
    }

    @Test
    public void deleteObject_refuses_an_unusable_name() {
        assertEquals(
                400,
                resource.deleteObject("foodmart", "foodmart", "foodmart", "Sales", null)
                        .getStatus());
        assertEquals(
                400,
                resource.deleteObject("foodmart", "foodmart", "foodmart", "Sales", "  ")
                        .getStatus());
    }

    @Test
    public void deleteObject_sanitises_and_never_escapes_the_tenant_folder() {
        // A traversal attempt cannot be refused with a path leak: the name is
        // sanitised into the tenant folder instead of escaping it.
        resource.deleteObject("foodmart", "foodmart", "foodmart", "Sales", "../../escape.saiku");
        assertTrue(ds.lastRemovePath.startsWith("/homes/admin/embed-guest-tenant-1/"));
    }

    /* ---------------------------- stubs ---------------------------- */

    private static class StubSessionService extends SessionService {
        String lastRunAsUser;
        List<String> lastRunAsRoles;

        @Override
        public <T> T runAs(String username, List<String> roles, Supplier<T> action) {
            lastRunAsUser = username;
            lastRunAsRoles = roles;
            return action.get();
        }
    }

    private static class StubDatasourceService extends DatasourceService {
        String saveResponse = "Save Okay";
        String removeResponse = "Remove Okay";
        String fileData = "{}";
        boolean filesFail;
        List<IRepositoryObject> files;
        String lastSavePath;
        String lastSaveContent;
        String lastSaveUser;
        List<String> lastSaveRoles;
        String lastRemovePath;

        @Override
        public String saveFile(String content, String path, String name, List<String> roles) {
            lastSavePath = path;
            lastSaveContent = content;
            lastSaveUser = name;
            lastSaveRoles = roles;
            return saveResponse;
        }

        @Override
        public String removeFile(String path, String name, List<String> roles) {
            lastRemovePath = path;
            return removeResponse;
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return fileData;
        }

        @Override
        public List<IRepositoryObject> getFiles(List<String> type, String username, List<String> roles, String path) {
            if (filesFail) {
                throw new IllegalStateException("repository down");
            }
            return files;
        }
    }

    private static class StubThinQueryService extends ThinQueryService {
        boolean failExecute;
        ThinQuery lastCreated;

        @Override
        public ThinQuery createQuery(ThinQuery tq) {
            lastCreated = tq;
            return tq;
        }

        @Override
        public CellDataSet execute(ThinQuery tq) {
            if (failExecute) {
                throw new IllegalStateException("no such measure");
            }
            return new CellDataSet(1, 1);
        }
    }

    private static class StubAiQueryResource extends AiQueryResource {
        String lastFormat;

        @Override
        public AiQueryResponse buildResponse(ThinQuery tq, CellDataSet cds, long startedAt, String format) {
            lastFormat = format;
            return new AiQueryResponse();
        }
    }

    private static class StubAuditLog extends AiAuditLog {
        AiAuditEntry last;

        @Override
        public void record(AiAuditEntry entry) {
            last = entry;
        }
    }

    /** Serves one visible dimension ([Store] with [Store].[Store]) and one visible measure. */
    private static class StubDiscoverService extends OlapDiscoverService {
        boolean fail;

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) {
            if (fail) {
                throw new IllegalStateException("cube unreachable");
            }
            SaikuLevel level =
                    new SaikuLevel("Store", "[Store].[Store]", "Store", null, "[Store]", "[Store]", true, null, null);
            SaikuHierarchy hierarchy =
                    new SaikuHierarchy("Store", "[Store]", "Store", null, "[Store]", true, List.of(level), null);
            return List.of(new SaikuDimension("Store", "[Store]", "Store", null, true, List.of(hierarchy)));
        }

        @Override
        public List<SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, int searchLimit) {
            if (fail) {
                throw new IllegalStateException("members unreachable");
            }
            return List.of(new SimpleCubeElement("USA", "[Store].[USA]", "USA"));
        }

        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            if (fail) {
                throw new IllegalStateException("measures unreachable");
            }
            return List.of(new SaikuMember(
                    "Unit Sales", "[Measures].[Unit Sales]", "Unit Sales", null, null, null, null, false));
        }
    }

    private static void assertHardened(Response r) {
        assertEquals("nosniff", r.getHeaderString("X-Content-Type-Options"));
        assertEquals("no-referrer", r.getHeaderString("Referrer-Policy"));
        assertEquals("no-store, max-age=0", r.getHeaderString("Cache-Control"));
    }
}
