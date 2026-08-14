/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end integration test for the App Builder flow (saiku#1441) at the REST +
 * embed layer against a REAL Saiku backend with the seeded FoodMart cube.
 *
 * <p>Threads the whole app lifecycle the way the SvelteKit UI + {@code <saiku-embed>}
 * bundle actually use it:
 *
 * <ol>
 *   <li>build a {@code .saikuapp} document with one inline-query table tile bound to
 *       the FoodMart Sales cube;</li>
 *   <li>{@code POST /rest/saiku/api/apps/{path}} → save;</li>
 *   <li>{@code GET /rest/saiku/api/apps/{path}} → the page/tile grid round-trips
 *       verbatim (guards the opaque-doc field-loss regression, saiku#1179);</li>
 *   <li>{@code GET /rest/saiku/api/apps} → the catalogue includes the saved app;</li>
 *   <li>embed leg: mint an {@code app} token ({@code POST /embed/tokens}, the Task-1
 *       fix), then with only the {@code X-Saiku-Embed-Token} guest header
 *       {@code GET /embed/app/{path}} returns the doc and
 *       {@code POST /embed/app/{path}/page/{pageId}/tile/{tileId}/query} returns real
 *       FoodMart cells;</li>
 *   <li>{@code DELETE /rest/saiku/api/apps/{path}} → removed (subsequent GET 404).</li>
 * </ol>
 *
 * <p><b>Auth note.</b> Saiku's {@code AppResource} and {@code EmbedTokenResource} both
 * derive the caller's username/roles from {@code SessionService.getAllSessionObjects()},
 * which is empty under bare Basic auth (see {@code SessionIT}) — so a save would fail the
 * repository {@code canWrite} gate and a mint would fail the {@code canGrant} gate. We
 * therefore seed the server-side session with one form-login as admin in
 * {@link #boot()}; every subsequent Basic-auth call then resolves {@code username=admin}
 * (the same session-holder projection the SPA relies on), and Basic-auth POSTs are
 * CSRF-exempt so no XSRF-token juggling is needed. {@link #cleanup()} logs the admin
 * session out again so downstream ITs still see the stateless-Basic posture.
 */
public class AppBuilderIT {

    private static final ObjectMapper M = new ObjectMapper();

    /** Same FoodMart cube id the AI Query ITs prove resolves real rows. */
    private static final String CUBE = "unknown_foodmart/FoodMart/FoodMart/Sales";

    private static final String APPS_BASE = "/rest/saiku/api/apps";
    private static final String EMBED_TOKEN_HEADER = "X-Saiku-Embed-Token";

    /** The seed tile plugin the launcher stages under {@code saiku-home/tile-plugins/} in demo mode. */
    private static final String PLUGIN_ID = "records-bars";

    private static final String TEXT_HTML = "text/html";

    private static SaikuItHarness harness;
    private static HttpClient guestClient;

    /** Repository path WITHOUT a leading slash — the form the {@code /apps/{path}} URL carries. */
    private static String appRepoPath;

    private static String appFileName;

    /** Same repo file, leading-slash form — what {@code EmbedAuthFilter} reconstructs from the URL. */
    private static String embedResourcePath;

    /** A SECOND app carrying custom (echarts-option) + plugin (records-bars) tiles — Phase 2. */
    private static String customAppRepoPath;

    private static String customAppFileName;

    private static String customEmbedResourcePath;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
        // Seed the server-side session so Basic-auth app CRUD + embed mint resolve username=admin.
        formLoginAsAdmin();
        // A cookie-less, auth-less client for the guest embed reads — the token header is the
        // ONLY credential, exactly as a third-party host page presents it.
        guestClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // saiku#1738: Windows-safe repository segment (no colon) — see SaikuItHarness#adminHomePath.
        appFileName = "it-app-" + System.nanoTime() + ".saikuapp";
        appRepoPath = SaikuItHarness.adminHomePath(appFileName);
        embedResourcePath = "/" + appRepoPath;

        customAppFileName = "it-app-custom-" + System.nanoTime() + ".saikuapp";
        customAppRepoPath = SaikuItHarness.adminHomePath(customAppFileName);
        customEmbedResourcePath = "/" + customAppRepoPath;

        // Stage the REAL records-bars seed plugin into the harness home so the embed plugin-html
        // delivery path can be proven end-to-end. The launcher only stages it in demo mode
        // (stageDemoDashboards), and the IT harness boots WITHOUT demo mode — so without this the
        // TilePluginRegistry (rooted at ${saiku.home}/tile-plugins) has no plugins and every
        // plugin-html fetch 404s regardless of the endpoint working. The bytes copied here are the
        // exact classpath seed resources the launcher stages; the registry rescans lazily on its
        // directory signature, so a plugin dropped in after boot is picked up on the next fetch.
        stageRecordsBarsPlugin();
    }

    @AfterClass
    public static void cleanup() throws Exception {
        // Best-effort: drop both apps (if a mid-test failure left them) and the seeded admin session.
        try {
            harness.deleteAuth(APPS_BASE + "/" + appRepoPath);
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            harness.deleteAuth(APPS_BASE + "/" + customAppRepoPath);
        } catch (Exception ignored) {
            /* best-effort */
        }
        try {
            harness.deleteAuth("/rest/saiku/session");
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    /**
     * Copy the {@code records-bars} seed plugin bundle ({@code plugin.json} + {@code plugin.html})
     * from the launcher classpath into {@code <saiku.home>/tile-plugins/records-bars/}. Idempotent —
     * skips a file that already exists so a re-run against a reused JVM leaves it alone.
     */
    private static void stageRecordsBarsPlugin() throws Exception {
        Path pluginDir = harness.home().resolve("tile-plugins").resolve(PLUGIN_ID);
        Files.createDirectories(pluginDir);
        copyClasspathResource("/seed/tile-plugins/" + PLUGIN_ID + "/plugin.json", pluginDir.resolve("plugin.json"));
        copyClasspathResource("/seed/tile-plugins/" + PLUGIN_ID + "/plugin.html", pluginDir.resolve("plugin.html"));
    }

    private static void copyClasspathResource(String resource, Path target) throws Exception {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = AppBuilderIT.class.getResourceAsStream(resource)) {
            assertNotNull("seed resource missing from classpath: " + resource, in);
            Files.copy(in, target);
        }
    }

    @Test
    public void appBuilder_restAndEmbedRoundTrip() throws Exception {
        ObjectNode app = buildApp();
        String appJson = M.writeValueAsString(app);

        // 2. Save the app document.
        HttpResponse<String> save = harness.postAuthJson(APPS_BASE + "/" + appRepoPath, appJson);
        assertEquals("save must return 200 — body: " + save.body(), 200, save.statusCode());
        assertEquals("OK", harness.parse(save).path("status").asText());

        // 3. Load it back and assert the doc round-trips VERBATIM (opaque-doc field-loss guard).
        HttpResponse<String> get = harness.getAuth(APPS_BASE + "/" + appRepoPath);
        assertEquals("load must return 200 — body: " + get.body(), 200, get.statusCode());
        JsonNode loaded = harness.parse(get);
        assertEquals("pages/tiles grid must round-trip verbatim", app.get("pages"), loaded.get("pages"));
        assertEquals("nav must round-trip verbatim", app.get("nav"), loaded.get("nav"));
        assertEquals("theme must round-trip verbatim", app.get("theme"), loaded.get("theme"));
        assertEquals("assistantSlot must round-trip verbatim", app.get("assistantSlot"), loaded.get("assistantSlot"));

        // 4. The catalogue lists the saved app.
        HttpResponse<String> list = harness.getAuth(APPS_BASE);
        assertEquals("list must return 200 — body: " + list.body(), 200, list.statusCode());
        assertTrue(
                "app catalogue must include the saved app '" + appFileName + "' — body: " + list.body(),
                list.body().contains(appFileName));

        // 5. Embed leg — mint an app token (Task-1 fix), then read + query as a guest.
        String token = mintEmbedToken();

        HttpResponse<String> embedApp = guestGet("/rest/saiku/api/embed/app/" + appRepoPath, token);
        assertEquals("embed app GET must return 200 — body: " + embedApp.body(), 200, embedApp.statusCode());
        JsonNode embedDoc = M.readTree(embedApp.body());
        assertEquals("embed app doc must match the saved pages grid", app.get("pages"), embedDoc.get("pages"));

        // The embed tile query is a state-changing POST. We issue it EXACTLY as the real
        // <saiku-embed> JS bundle does (saiku-ui/src/embed/api.ts): credentials omitted, no
        // session cookie, no XSRF token — the embed token in X-Saiku-Embed-Token is the sole
        // credential. That carries no ambient cookie for CSRF to protect, so SaikuCsrfRequestMatcher
        // exempts the embed read prefixes; a 403 here is the regression this asserts against
        // (CsrfFilter runs before embedAuthFilter, so a mis-scoped exemption 403s the guest before
        // the token is ever inspected).
        HttpResponse<String> tileQuery =
                guestPost("/rest/saiku/api/embed/app/" + appRepoPath + "/page/p1/tile/t1/query", token, "{}");
        assertNotEquals(
                "token-only embed tile POST must NOT be CSRF-blocked (403) — the guest carries no cookie to"
                        + " protect; body: " + tileQuery.body(),
                403,
                tileQuery.statusCode());
        assertEquals("embed tile query must return 200 — body: " + tileQuery.body(), 200, tileQuery.statusCode());
        JsonNode tile = M.readTree(tileQuery.body());
        assertEquals(
                "embed tile query must SUCCEED against FoodMart — body: " + tileQuery.body(),
                "SUCCESS",
                tile.path("status").asText());
        JsonNode data = tile.path("data");
        assertTrue(
                "embed tile query must return a non-empty records array — body: " + tileQuery.body(),
                data.isArray() && data.size() > 0);
        double firstStoreSales = data.get(0).path("Store Sales").path("value").asDouble();
        assertTrue("first FoodMart row's Store Sales must be > 0, got " + firstStoreSales, firstStoreSales > 0);

        // 6. Delete removes it — subsequent load 404s.
        HttpResponse<String> del = harness.deleteAuth(APPS_BASE + "/" + appRepoPath);
        assertEquals("delete must return 200 — body: " + del.body(), 200, del.statusCode());
        HttpResponse<String> afterDelete = harness.getAuth(APPS_BASE + "/" + appRepoPath);
        assertEquals("load after delete must 404", 404, afterDelete.statusCode());
    }

    /**
     * Phase 2 (custom + plugin tiles). Builds a {@code .saikuapp} whose pages carry a
     * {@code type:"custom"} <b>echarts-option</b> tile (with the SAME proven inline FoodMart query
     * as the table tile) and a {@code type:"custom"} <b>plugin</b> tile referencing the staged
     * {@code records-bars} seed plugin, then proves:
     *
     * <ol>
     *   <li>the custom {@code renderer} + {@code options} blocks round-trip <b>verbatim</b> (guards
     *       opaque-doc field loss for the new custom fields — saiku#1179);</li>
     *   <li>the embed tile-query for the custom echarts tile resolves real FoodMart cells — an
     *       isQueryable custom tile runs through the exact same executor as the table tile;</li>
     *   <li>the embed plugin-html endpoint returns {@code 200 text/html} for the referenced plugin
     *       (token-scoped, app-referenced delivery + the {@code EmbedAuthFilter} {@code /plugin/}
     *       strip working in-process), and {@code 404} for an unreferenced plugin id.</li>
     * </ol>
     */
    @Test
    public void appBuilder_customAndPluginTiles_roundTripQueryAndPluginHtml() throws Exception {
        ObjectNode app = buildCustomApp();
        String appJson = M.writeValueAsString(app);

        // Save + verbatim round-trip — the pages grid now carries the custom renderer + options
        // blocks, so this assertion is the field-loss guard for the NEW custom fields.
        HttpResponse<String> save = harness.postAuthJson(APPS_BASE + "/" + customAppRepoPath, appJson);
        assertEquals("save must return 200 — body: " + save.body(), 200, save.statusCode());
        assertEquals("OK", harness.parse(save).path("status").asText());

        HttpResponse<String> get = harness.getAuth(APPS_BASE + "/" + customAppRepoPath);
        assertEquals("load must return 200 — body: " + get.body(), 200, get.statusCode());
        JsonNode loaded = harness.parse(get);
        assertEquals("custom + plugin pages grid must round-trip verbatim", app.get("pages"), loaded.get("pages"));

        // Explicit spot-check that the custom block survived the opaque-doc round-trip.
        JsonNode echartsTile =
                loaded.get("pages").get(0).get("grid").get("tiles").get(1);
        assertEquals(
                "second tile must be a custom tile",
                "custom",
                echartsTile.path("type").asText());
        JsonNode custom = echartsTile.path("custom");
        assertEquals(
                "renderer must survive",
                "echarts-option",
                custom.path("renderer").asText());
        assertTrue(
                "options.series must survive verbatim",
                custom.path("options").path("series").isArray()
                        && custom.path("options").path("series").size() > 0);

        // Embed leg — mint an app token for THIS app, then read + query as a guest.
        String token = mintEmbedToken(customEmbedResourcePath, "app-builder-custom-it");

        HttpResponse<String> embedApp = guestGet("/rest/saiku/api/embed/app/" + customAppRepoPath, token);
        assertEquals("embed app GET must return 200 — body: " + embedApp.body(), 200, embedApp.statusCode());

        // The custom echarts tile (t2 on p1) is queryable — it runs the SAME executor as the table
        // tile and must resolve real FoodMart Store-Sales cells.
        HttpResponse<String> tileQuery =
                guestPost("/rest/saiku/api/embed/app/" + customAppRepoPath + "/page/p1/tile/t2/query", token, "{}");
        assertEquals(
                "custom echarts tile embed query must return 200 — body: " + tileQuery.body(),
                200,
                tileQuery.statusCode());
        JsonNode tile = M.readTree(tileQuery.body());
        assertEquals(
                "custom tile embed query must SUCCEED against FoodMart — body: " + tileQuery.body(),
                "SUCCESS",
                tile.path("status").asText());
        JsonNode data = tile.path("data");
        assertTrue(
                "custom tile embed query must return a non-empty records array — body: " + tileQuery.body(),
                data.isArray() && data.size() > 0);
        double firstStoreSales = data.get(0).path("Store Sales").path("value").asDouble();
        assertTrue("first FoodMart row's Store Sales must be > 0, got " + firstStoreSales, firstStoreSales > 0);

        // Plugin-html delivery — the plugin tile (t3 on p2) references records-bars, which is staged
        // in the harness home, so the token-scoped app-referenced fetch must return 200 text/html.
        HttpResponse<String> pluginHtml = guestGetAccept(
                "/rest/saiku/api/embed/app/" + customAppRepoPath + "/plugin/" + PLUGIN_ID + "/html", token, TEXT_HTML);
        assertEquals(
                "referenced plugin-html must return 200 — body: " + pluginHtml.body(), 200, pluginHtml.statusCode());
        String contentType = pluginHtml.headers().firstValue("content-type").orElse("");
        assertTrue(
                "plugin-html content-type must be text/html — was: " + contentType,
                contentType.toLowerCase().contains("text/html"));
        assertTrue(
                "plugin-html body must be the staged srcdoc HTML — body: " + pluginHtml.body(),
                pluginHtml.body().contains("<!DOCTYPE html>")
                        || pluginHtml.body().contains("<html"));

        // An UNREFERENCED (but slug-valid, installed-or-not) plugin id must 404 — a guest can only
        // fetch a plugin the PINNED app actually references, never probe for other plugins.
        HttpResponse<String> unreferenced = guestGetAccept(
                "/rest/saiku/api/embed/app/" + customAppRepoPath + "/plugin/unreferenced-plugin/html",
                token,
                TEXT_HTML);
        assertEquals("unreferenced plugin id must 404 — body: " + unreferenced.body(), 404, unreferenced.statusCode());

        // Delete removes it.
        HttpResponse<String> del = harness.deleteAuth(APPS_BASE + "/" + customAppRepoPath);
        assertEquals("delete must return 200 — body: " + del.body(), 200, del.statusCode());
    }

    /* ------------------------------ builders ------------------------------ */

    /** A minimal-but-complete {@code .saikuapp} with one inline-query table tile on FoodMart Sales. */
    private static ObjectNode buildApp() {
        ObjectNode app = M.createObjectNode();
        app.put("name", "IT App Builder");
        app.put("version", "1.0.0");
        app.set("theme", M.createObjectNode().put("mode", "auto"));
        app.set("nav", M.createObjectNode().put("position", "rail"));
        app.set("assistantSlot", M.createObjectNode().put("enabled", false));

        ObjectNode cube = M.createObjectNode();
        cube.put("connectionName", "unknown_foodmart");
        cube.put("catalog", "FoodMart");
        cube.put("schema", "FoodMart");
        cube.put("cubeName", "Sales");

        // Proven FoodMart AiQueryRequest body (from AiQueryIT) — measure-by-product-family.
        ObjectNode inlineBody = M.createObjectNode();
        inlineBody.put("cube", CUBE);
        ArrayNode measures = inlineBody.putArray("measures");
        measures.add(M.createObjectNode().put("name", "Store Sales"));
        ArrayNode rows = inlineBody.putArray("rows");
        ObjectNode productRow = M.createObjectNode();
        productRow.put("dimension", "Product");
        productRow.put("hierarchy", "Products");
        productRow.put("level", "Product Family");
        rows.add(productRow);

        ObjectNode query = M.createObjectNode();
        query.put("kind", "inline");
        query.set("body", inlineBody);

        ObjectNode tile = M.createObjectNode();
        tile.put("id", "t1");
        tile.put("type", "table");
        tile.put("x", 0);
        tile.put("y", 0);
        tile.put("w", 6);
        tile.put("h", 4);
        tile.set("cube", cube);
        tile.set("query", query);

        ObjectNode grid = M.createObjectNode();
        grid.put("cols", 12);
        grid.putArray("tiles").add(tile);

        ObjectNode page = M.createObjectNode();
        page.put("id", "p1");
        page.put("title", "Overview");
        page.set("grid", grid);

        app.putArray("pages").add(page);
        app.putArray("tags");
        return app;
    }

    /**
     * A {@code .saikuapp} carrying the Phase-2 custom + plugin tiles:
     *
     * <ul>
     *   <li>page {@code p1}: a table tile ({@code t1}) plus a {@code type:"custom"} echarts-option
     *       tile ({@code t2}) that binds the SAME proven inline FoodMart query, so it is queryable
     *       through the exact same embed executor;</li>
     *   <li>page {@code p2}: a {@code type:"custom"} plugin tile ({@code t3}) referencing the staged
     *       {@code records-bars} seed plugin — this is what the embed plugin-html endpoint keys off.</li>
     * </ul>
     */
    private static ObjectNode buildCustomApp() {
        ObjectNode app = M.createObjectNode();
        app.put("name", "IT App Builder — Custom");
        app.put("version", "1.0.0");
        app.set("theme", M.createObjectNode().put("mode", "auto"));
        app.set("nav", M.createObjectNode().put("position", "rail"));
        app.set("assistantSlot", M.createObjectNode().put("enabled", false));

        // ---- page p1: table + custom echarts-option tile ----
        ObjectNode tableTile = tile("t1", "table", 0, 0);
        tableTile.set("cube", foodMartCube());
        tableTile.set("query", inlineStoreSalesQuery());

        ObjectNode echartsTile = tile("t2", "custom", 6, 0);
        echartsTile.set("cube", foodMartCube());
        echartsTile.set("query", inlineStoreSalesQuery());
        // A minimal-but-valid safe ECharts bar option — the payload the echarts-option renderer maps.
        ObjectNode echartsOptions = M.createObjectNode();
        echartsOptions.putArray("series").add(M.createObjectNode().put("type", "bar"));
        ObjectNode echartsCustom = M.createObjectNode();
        echartsCustom.put("renderer", "echarts-option");
        echartsCustom.set("options", echartsOptions);
        echartsTile.set("custom", echartsCustom);

        ObjectNode grid1 = M.createObjectNode();
        grid1.put("cols", 12);
        ArrayNode tiles1 = grid1.putArray("tiles");
        tiles1.add(tableTile);
        tiles1.add(echartsTile);

        ObjectNode page1 = M.createObjectNode();
        page1.put("id", "p1");
        page1.put("title", "Overview");
        page1.set("grid", grid1);

        // ---- page p2: custom plugin tile (records-bars) ----
        ObjectNode pluginTile = tile("t3", "custom", 0, 0);
        pluginTile.set("cube", foodMartCube());
        pluginTile.set("query", inlineStoreSalesQuery());
        ObjectNode pluginOptions = M.createObjectNode();
        pluginOptions.put("pluginId", PLUGIN_ID);
        ObjectNode pluginCustom = M.createObjectNode();
        pluginCustom.put("renderer", "plugin");
        pluginCustom.set("options", pluginOptions);
        pluginTile.set("custom", pluginCustom);

        ObjectNode grid2 = M.createObjectNode();
        grid2.put("cols", 12);
        grid2.putArray("tiles").add(pluginTile);

        ObjectNode page2 = M.createObjectNode();
        page2.put("id", "p2");
        page2.put("title", "Plugin");
        page2.set("grid", grid2);

        ArrayNode pages = app.putArray("pages");
        pages.add(page1);
        pages.add(page2);
        app.putArray("tags");
        return app;
    }

    /** A tile skeleton at the given grid position with the standard 6×4 span. */
    private static ObjectNode tile(String id, String type, int x, int y) {
        ObjectNode tile = M.createObjectNode();
        tile.put("id", id);
        tile.put("type", type);
        tile.put("x", x);
        tile.put("y", y);
        tile.put("w", 6);
        tile.put("h", 4);
        return tile;
    }

    /** The FoodMart Sales cube ref every tile binds to. */
    private static ObjectNode foodMartCube() {
        ObjectNode cube = M.createObjectNode();
        cube.put("connectionName", "unknown_foodmart");
        cube.put("catalog", "FoodMart");
        cube.put("schema", "FoodMart");
        cube.put("cubeName", "Sales");
        return cube;
    }

    /** The proven inline Store-Sales-by-Product-Family query (from AiQueryIT). */
    private static ObjectNode inlineStoreSalesQuery() {
        ObjectNode inlineBody = M.createObjectNode();
        inlineBody.put("cube", CUBE);
        inlineBody.putArray("measures").add(M.createObjectNode().put("name", "Store Sales"));
        ObjectNode productRow = M.createObjectNode();
        productRow.put("dimension", "Product");
        productRow.put("hierarchy", "Products");
        productRow.put("level", "Product Family");
        inlineBody.putArray("rows").add(productRow);

        ObjectNode query = M.createObjectNode();
        query.put("kind", "inline");
        query.set("body", inlineBody);
        return query;
    }

    private static String mintEmbedToken() throws Exception {
        return mintEmbedToken(embedResourcePath, "app-builder-it");
    }

    private static String mintEmbedToken(String resourcePath, String label) throws Exception {
        ObjectNode mint = M.createObjectNode();
        mint.put("resourceKind", "app");
        mint.put("resourcePath", resourcePath);
        mint.put("label", label);
        HttpResponse<String> resp = harness.postAuthJson("/rest/saiku/api/embed/tokens", M.writeValueAsString(mint));
        assertEquals("embed token mint must return 200 — body: " + resp.body(), 200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("OK", body.path("status").asText());
        String token = body.path("token").asText();
        assertFalse("mint must return a non-blank token — body: " + resp.body(), token == null || token.isBlank());
        return token;
    }

    /* ------------------------------- clients ------------------------------ */

    private static void formLoginAsAdmin() throws Exception {
        HttpClient loginClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest login = HttpRequest.newBuilder(URI.create(harness.baseUrl() + "/rest/saiku/session"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=admin", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = loginClient.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals("admin form-login must succeed (200) — body: " + resp.body(), 200, resp.statusCode());
    }

    private static HttpResponse<String> guestGet(String path, String token) throws Exception {
        return guestGetAccept(path, token, "application/json");
    }

    /**
     * A guest GET with an explicit {@code Accept}. The plugin-html endpoint {@code @Produces
     * text/html}, so an {@code Accept: application/json} would 406 on JAX-RS content negotiation
     * before the resource ever runs — the real embed bundle fetches plugin HTML with
     * {@code Accept: text/html}, so the IT must too.
     */
    private static HttpResponse<String> guestGetAccept(String path, String token, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", accept)
                .header(EMBED_TOKEN_HEADER, token)
                .GET()
                .build();
        return guestClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A guest tile-query POST issued the way the {@code <saiku-embed>} bundle issues it:
     * {@code credentials:"omit"} (no cookie jar on {@link #guestClient}), only the embed
     * token header. Deliberately NO XSRF header — the real bundle sends none, so the IT
     * must not either, or it would pass while the shipped bundle 403s.
     */
    private static HttpResponse<String> guestPost(String path, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(harness.baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(EMBED_TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return guestClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
