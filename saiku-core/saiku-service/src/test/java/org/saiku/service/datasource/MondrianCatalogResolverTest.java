/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * saiku#1844 — the lookup rule for {@code Catalog=mondrian://…} references.
 *
 * <p>These references are what the admin UI writes for every Mondrian data source, and until this
 * class existed nothing on the connect path could resolve them.
 */
public class MondrianCatalogResolverTest {

    // --- isRepositoryReference -------------------------------------------------

    @Test
    public void treatsTheMondrianSchemeAsARepositoryReference() {
        assertTrue(MondrianCatalogResolver.isRepositoryReference("mondrian:///datasources/Foo.xml"));
        assertTrue(MondrianCatalogResolver.isRepositoryReference("mondrian://Foo"));
    }

    @Test
    public void treatsABareRepositoryPathAsARepositoryReference() {
        assertTrue(MondrianCatalogResolver.isRepositoryReference("/datasources/Foo.xml"));
        assertTrue(MondrianCatalogResolver.isRepositoryReference("Foo"));
    }

    @Test
    public void leavesSchemesMondrianAlreadyHandlesAlone() {
        // These must fall through to Mondrian's own file handler untouched — hijacking them
        // would break every seeded data source, which uses Catalog=file:.
        assertFalse(MondrianCatalogResolver.isRepositoryReference("file:/data/FoodMart4.xml"));
        assertFalse(MondrianCatalogResolver.isRepositoryReference("http://host/schema.xml"));
        assertFalse(MondrianCatalogResolver.isRepositoryReference("res:foodmart.xml"));
        assertFalse(MondrianCatalogResolver.isRepositoryReference("jar:file:/a.jar!/b.xml"));
    }

    @Test
    public void doesNotMistakeAWindowsDriveLetterForAScheme() {
        // "C:/schemas/x.xml" has a colon at index 1, same as a one-char scheme. It is a path.
        assertTrue(MondrianCatalogResolver.isRepositoryReference("C:/schemas/x.xml"));
    }

    @Test
    public void nullAndBlankAreNotReferences() {
        assertFalse(MondrianCatalogResolver.isRepositoryReference(null));
        assertFalse(MondrianCatalogResolver.isRepositoryReference("   "));
    }

    // --- candidatePaths --------------------------------------------------------

    @Test
    public void fullPathBehindTheSchemeResolvesVerbatimFirst() {
        // The shape DataSourceMapper produces when the admin's schema field is a path.
        List<String> c = MondrianCatalogResolver.candidatePaths("mondrian:///datasources/Foo.xml");
        assertEquals("/datasources/Foo.xml", c.get(0));
    }

    @Test
    public void bareNameFallsBackToTheConventionalAddSchemaLocation() {
        // addSchema writes uploads to /datasources/<name>.xml, so "mondrian://Foo" means that file.
        List<String> c = MondrianCatalogResolver.candidatePaths("mondrian://Foo");
        assertEquals(List.of("/Foo", "/datasources/Foo.xml"), c);
    }

    @Test
    public void neverProposesTheSamePathTwice() {
        // Each candidate costs a repository read, so a duplicate is a wasted round trip
        // against a backend that may be remote (Cloud routes these through Postgres).
        for (String ref : List.of("mondrian://Foo", "mondrian:///datasources/Foo.xml", "datasources/Foo.xml", "/Foo")) {
            List<String> c = MondrianCatalogResolver.candidatePaths(ref);
            assertEquals(
                    "duplicate candidate for " + ref,
                    c.size(),
                    c.stream().distinct().count());
        }
    }

    @Test
    public void emptyReferenceYieldsNoCandidates() {
        assertTrue(MondrianCatalogResolver.candidatePaths("mondrian://").isEmpty());
        assertTrue(MondrianCatalogResolver.candidatePaths(null).isEmpty());
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    public void resolvesThroughTheSuppliedFetcher() {
        Map<String, String> repo = new HashMap<>();
        repo.put("/datasources/Foo.xml", "<Schema name='Foo'/>");
        assertEquals("<Schema name='Foo'/>", MondrianCatalogResolver.resolve("mondrian://Foo", repo::get));
    }

    @Test
    public void resolvesAFullPathReference() {
        Map<String, String> repo = new HashMap<>();
        repo.put("/datasources/Foo.xml", "<Schema name='Foo'/>");
        assertEquals(
                "<Schema name='Foo'/>", MondrianCatalogResolver.resolve("mondrian:///datasources/Foo.xml", repo::get));
    }

    @Test
    public void returnsNullWhenNothingResolves() {
        assertNull(MondrianCatalogResolver.resolve("mondrian://Missing", p -> null));
    }

    @Test
    public void treatsABlankRepositoryEntryAsAbsent() {
        // A truncated / empty schema file must not be handed to Mondrian as if it were valid.
        assertNull(MondrianCatalogResolver.resolve("mondrian://Foo", p -> "   "));
    }
}
