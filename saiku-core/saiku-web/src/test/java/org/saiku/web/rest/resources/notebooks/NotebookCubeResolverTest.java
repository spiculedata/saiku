/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Unit coverage for {@link NotebookCubeResolver} (issue #1108) — the seam that
 * turns a notebook MDX cell's client-authored {@link AiCubeRef} into a live
 * {@link SaikuCube} without ever trusting a client-supplied SaikuCube (which
 * has no JSON setters; see the resolver's javadoc).
 */
public class NotebookCubeResolverTest {

    private static SaikuCube cube(String connection, String catalog, String schema, String name) {
        return new SaikuCube(connection, name, name, name + " caption", catalog, schema);
    }

    private static OlapDiscoverService fixedCatalogue(List<SaikuCube> cubes) {
        return new OlapDiscoverService() {
            @Override
            public List<SaikuCube> getAllCubes() {
                return cubes;
            }
        };
    }

    @Test
    public void resolve_matchesOnConnectionCatalogSchemaAndCubeName() {
        SaikuCube sales = cube("FoodMart", "FoodMart", "FoodMart", "Sales");
        SaikuCube warehouse = cube("FoodMart", "FoodMart", "FoodMart", "Warehouse");
        OlapDiscoverService svc = fixedCatalogue(List.of(sales, warehouse));

        SaikuCube resolved =
                NotebookCubeResolver.resolve(svc, new AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales"));
        assertSame(sales, resolved);
    }

    @Test
    public void resolve_isCaseInsensitiveOnCubeCatalogAndSchema() {
        SaikuCube sales = cube("FoodMart", "FoodMart", "FoodMart", "Sales");
        OlapDiscoverService svc = fixedCatalogue(List.of(sales));

        SaikuCube resolved =
                NotebookCubeResolver.resolve(svc, new AiCubeRef("FoodMart", "foodmart", "FOODMART", "sales"));
        assertSame(sales, resolved);
    }

    @Test
    public void resolve_returnsNullWhenNoCubeMatches() {
        OlapDiscoverService svc = fixedCatalogue(List.of(cube("FoodMart", "FoodMart", "FoodMart", "Sales")));
        assertNull(NotebookCubeResolver.resolve(
                svc, new AiCubeRef("FoodMart", "FoodMart", "FoodMart", "NoSuchCube")));
    }

    @Test
    public void resolve_returnsNullOnNullRefOrNullService() {
        OlapDiscoverService svc = fixedCatalogue(List.of(cube("FoodMart", "FoodMart", "FoodMart", "Sales")));
        assertNull(NotebookCubeResolver.resolve(svc, null));
        assertNull(NotebookCubeResolver.resolve(null, new AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales")));
    }

    @Test
    public void resolve_returnsNullWhenDiscoveryThrows() {
        OlapDiscoverService svc = new OlapDiscoverService() {
            @Override
            public List<SaikuCube> getAllCubes() throws SaikuOlapException {
                throw new SaikuOlapException("boom");
            }
        };
        assertNull(NotebookCubeResolver.resolve(svc, new AiCubeRef("FoodMart", "FoodMart", "FoodMart", "Sales")));
    }

    @Test
    public void resolve_catalogAndSchemaAreOptionalOnTheRef() {
        SaikuCube sales = cube("FoodMart", "FoodMart", "FoodMart", "Sales");
        OlapDiscoverService svc = fixedCatalogue(List.of(sales));

        // A ref with only connectionName + cubeName set (catalog/schema null)
        // still resolves — the resolver only checks the fields the ref sets.
        AiCubeRef ref = new AiCubeRef();
        ref.setConnectionName("FoodMart");
        ref.setCubeName("Sales");
        assertEquals(sales, NotebookCubeResolver.resolve(svc, ref));
    }
}
