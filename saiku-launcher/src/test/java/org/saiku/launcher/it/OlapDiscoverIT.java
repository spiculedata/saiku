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
            if ("unknown_foodmart".equals(c.path("name").asText())) {
                foodmartFound = true;
                break;
            }
        }
        assertTrue("unknown_foodmart connection must be present, body=" + resp.body(), foodmartFound);
    }

    @Test
    public void listSingleConnection_returnsSpecifiedConnection() throws Exception {
        HttpResponse<String> resp = harness.getAuth(BASE + "/unknown_foodmart");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue("response must be a single-element array", body.isArray());
        assertEquals(1, body.size());
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
            if ("unknown_foodmart".equals(c.path("name").asText())) {
                foodmartFound = true;
                break;
            }
        }
        assertTrue("unknown_foodmart must still be in the connection list after refresh", foodmartFound);
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
    public void unknownConnection_returnsEmptyArray() throws Exception {
        // OlapDiscoverResource swallows lookup exceptions and returns []
        // rather than 404 — that's the documented contract.
        HttpResponse<String> resp = harness.getAuth(BASE + "/no-such-conn");
        assertEquals(200, resp.statusCode());
        JsonNode body = harness.parse(resp);
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }
}
