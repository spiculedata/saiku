/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;

/**
 * saiku#1223 — the demo loaders (foodmart / bank / earthquakes) must skip re-registration when
 * the repository already carries the datasource. The tenant prefix ({@code unknown_}) is applied
 * after {@code addDatasource()}, so a NAME lookup misses the existing copy — the guard scans for
 * the well-known {@code id} property instead. These tests pin that scan
 * ({@link Database#containsDatasourceId}).
 */
public class DatabaseSeedGuardTest {

    private static SaikuDatasource ds(String name, String id) {
        Properties p = new Properties();
        if (id != null) {
            p.setProperty("id", id);
        }
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, p);
    }

    @Test
    public void findsIdRegardlessOfTenantPrefixedName() {
        // The dupe scenario: the disk scan registered "unknown_bank"; the loader must still
        // recognise it by UUID even though no datasource is literally named "bank".
        assertTrue(Database.containsDatasourceId(
                Arrays.asList(
                        ds("unknown_foodmart", "4432dd20-fcae-11e3-a3ac-0800200c9a66"),
                        ds("unknown_bank", "4432dd20-fcae-11e3-a3ac-0800200c9a68")),
                "4432dd20-fcae-11e3-a3ac-0800200c9a68"));
    }

    @Test
    public void absentIdReportsNotRegistered() {
        assertFalse(Database.containsDatasourceId(
                Collections.singletonList(ds("unknown_foodmart", "4432dd20-fcae-11e3-a3ac-0800200c9a66")),
                "4432dd20-fcae-11e3-a3ac-0800200c9a68"));
    }

    @Test
    public void toleratesNullsAndIdLessDatasources() {
        assertFalse(Database.containsDatasourceId(null, "x"));
        assertFalse(Database.containsDatasourceId(Collections.emptyList(), "x"));
        assertFalse(Database.containsDatasourceId(Collections.singletonList(null), "x"));
        assertFalse(Database.containsDatasourceId(Collections.singletonList(ds("no-id", null)), "x"));
        assertFalse(Database.containsDatasourceId(Collections.singletonList(ds("a", "some-id")), null));
    }
}
