/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Coverage for the legacy discovery surface ({@code /rest/saiku/{user}/discover/*}),
 * which the SPA uses to populate the cube tree, dimensions, hierarchies, levels
 * and member lists. All endpoints are read-only GETs against the seeded
 * FoodMart datasource.
 */
public class OlapDiscoverIT {

    private static SaikuItHarness harness;
    private static final String BASE = "/rest/saiku/admin/discover";
    // saiku#1871 dropped the `unknown_` workspace decoration, so discover now REPORTS `foodmart`.
    // These paths deliberately keep the OLD spelling: every saved query, dashboard and app in an
    // existing install has it baked in, so them still resolving is the property that makes the
    // rename safe. If the compatibility alias ever regresses, these are the tests that catch it.
    private static final String FOODMART_CUBE_PATH = "/unknown_foodmart/FoodMart/FoodMart/Sales";

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void listAllConnections_returnsArrayWithFoodmart() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE);
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("connections must be a JSON array", body.isArray());
        boolean foodmartFound = false;
        for (JsonNode c : body) {
            if ("foodmart".equals(c.path("name").asText())) {
                foodmartFound = true;
                break;
            }
        }
        assertTrue("foodmart connection must be present, body=" + resp.body(), foodmartFound);
    }

    @Test
    public void listSingleConnection_returnsSpecifiedConnection() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/unknown_foodmart");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("response must be a single-element array", body.isArray());
        assertEquals(1, body.size());
        // saiku#1871: discover ECHOES the name you asked for. Requesting the legacy
        // `unknown_foodmart` alias therefore reports it back, so a pre-rename client sees names
        // consistent with the ones it already holds. Ask by the canonical `foodmart` and you get
        // `foodmart` — covered by listAllConnections_returnsArrayWithFoodmart above.
        assertEquals("unknown_foodmart", body.get(0).path("name").asText());
    }

    @Test
    public void refreshAllConnections_returnsArray() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/refresh");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("refresh response must be an array", body.isArray());
    }

    @Test
    public void refreshSingleConnection_returnsArrayContainingIt() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/unknown_foodmart/refresh");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue(body.isArray());
        boolean foodmartFound = false;
        for (JsonNode c : body) {
            // Echoed alias, as above — this endpoint was asked for `unknown_foodmart`.
            if ("unknown_foodmart".equals(c.path("name").asText())) {
                foodmartFound = true;
                break;
            }
        }
        assertTrue("the refreshed connection must still be listed", foodmartFound);
    }

    @Test
    public void cubeMetadata_returnsDimensionsAndMeasures() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + FOODMART_CUBE_PATH + "/metadata");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        // /metadata returns a CubeMetadata wrapper with dimensions + measures.
        assertTrue("metadata body must include dimensions key", body.has("dimensions"));
        assertTrue("metadata body must include measures key", body.has("measures"));
    }

    @Test
    public void cubeDimensions_includesProductTimeStoreCustomer() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + FOODMART_CUBE_PATH + "/dimensions");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("dimensions must be a JSON array", body.isArray());

        boolean foundProduct = false;
        boolean foundTime = false;
        for (JsonNode d : body) {
            String name = d.path("name").asText();
            if ("Product".equals(name)) foundProduct = true;
            if ("Time".equals(name)) foundTime = true;
        }
        assertTrue("Product dim must be present", foundProduct);
        assertTrue("Time dim must be present", foundTime);
    }

    @Test
    public void dimensionDetail_productReturnsSchemaWithHierarchies() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + FOODMART_CUBE_PATH + "/dimensions/Product");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("Product", body.path("name").asText());
    }

    @Test
    public void hierarchiesForDimension_productHasProductsHierarchy() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + FOODMART_CUBE_PATH + "/dimensions/Product/hierarchies");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue(body.isArray());
        boolean productsFound = false;
        for (JsonNode h : body) {
            if ("Products".equals(h.path("name").asText())) {
                productsFound = true;
                break;
            }
        }
        assertTrue("Products hierarchy must be present, body=" + resp.body(), productsFound);
    }

    @Test
    public void levelsForHierarchy_productsHasProductFamilyLevel() throws Exception {
        HttpResponse<String> resp =
                harness.getAuth(BASE + FOODMART_CUBE_PATH + "/dimensions/Product/hierarchies/Products/levels");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue(body.isArray());
        boolean familyFound = false;
        for (JsonNode l : body) {
            if ("Product Family".equals(l.path("name").asText())) {
                familyFound = true;
                break;
            }
        }
        assertTrue("Product Family level must be present", familyFound);
    }

    @Test
    public void unknownConnection_returns404WithTypedEnvelope() throws Exception {
        // saiku#867 fix: unknown connection now surfaces as 404 with
        // {status,field,value,error} envelope rather than a silent empty
        // array — clients can tell "no connection by this name" apart
        // from "connection has no schemas".
        HttpResponse<String> resp = harness.getAuth(BASE + "/no-such-conn");
        assertEquals(404, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertEquals("NOT_FOUND", body.path("status").asText());
        assertEquals("connection", body.path("field").asText());
        assertEquals("no-such-conn", body.path("value").asText());
    }
}
