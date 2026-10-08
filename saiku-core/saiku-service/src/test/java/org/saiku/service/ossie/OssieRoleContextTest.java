/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.service.util.exception.SaikuAccessDeniedException;

/** Unit tests for {@link OssieRoleContext} (saiku#1393) — row predicates, HIDE, discover filtering. */
public class OssieRoleContextTest {

    @Test
    public void rowPredicatesForReturnsOnlyMatchingRoles() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("geography");
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_APAC", "REGION = 'APAC'"));
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_EMEA", "REGION = 'EMEA'"));
        semantic.getDatasets().add(ds);

        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_APAC"), semantic);
        assertEquals(List.of("REGION = 'APAC'"), ctx.rowPredicatesFor("geography"));
        assertEquals(List.of("REGION = 'APAC'"), ctx.rowPredicatesFor("GEOGRAPHY")); // case-insensitive dataset match
    }

    @Test
    public void rowPredicatesForEmptyWhenNoRoleMatches() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("geography");
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_APAC", "REGION = 'APAC'"));
        semantic.getDatasets().add(ds);

        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_OTHER"), semantic);
        assertTrue(ctx.rowPredicatesFor("geography").isEmpty());
    }

    @Test
    public void rowPredicatesForEmptyForUnknownDataset() {
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_APAC"), new OssieModelDto());
        assertTrue(ctx.rowPredicatesFor("no-such-dataset").isEmpty());
    }

    @Test
    public void assertShelfVisibleThrowsForDeniedField() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("customers");
        OssieModelDto.Field ssn = new OssieModelDto.Field();
        ssn.setName("ssn");
        ssn.setDenyRoles(List.of("ROLE_EMBED_GUEST"));
        ds.getFields().add(ssn);
        semantic.getDatasets().add(ds);

        OssieQueryModel model = new OssieQueryModel();
        model.setFactDataset("customers");
        OssieQueryModel.FieldRef ref = new OssieQueryModel.FieldRef();
        ref.setDataset("customers");
        ref.setField("ssn");
        model.getRows().add(ref);

        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_EMBED_GUEST"), semantic);
        try {
            ctx.assertShelfVisible(model);
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException expected) {
            assertTrue(expected.getMessage().contains("customers.ssn"));
        }
    }

    @Test
    public void assertShelfVisiblePassesForUnrestrictedShelf() {
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of(), new OssieModelDto());
        ctx.assertShelfVisible(new OssieQueryModel()); // no exception
    }

    @Test
    public void filterHiddenDropsDeniedFieldsAndMetricsOnly() {
        OssieModelDto semantic = new OssieModelDto();

        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("customers");
        OssieModelDto.Field region = new OssieModelDto.Field();
        region.setName("region");
        OssieModelDto.Field ssn = new OssieModelDto.Field();
        ssn.setName("ssn");
        ssn.setAllowRoles(List.of("ROLE_ADMIN"));
        ds.getFields().add(region);
        ds.getFields().add(ssn);
        semantic.getDatasets().add(ds);

        OssieModelDto.Metric revenue = new OssieModelDto.Metric();
        revenue.setName("revenue");
        OssieModelDto.Metric execOnly = new OssieModelDto.Metric();
        execOnly.setName("exec_only");
        execOnly.setAllowRoles(List.of("ROLE_EXEC"));
        semantic.getMetrics().add(revenue);
        semantic.getMetrics().add(execOnly);

        OssieModelDto filtered = OssieRoleContext.filterHidden(semantic, Set.of("ROLE_ANALYST"));

        assertEquals(1, filtered.getDatasets().size());
        assertEquals(1, filtered.getDatasets().get(0).getFields().size());
        assertEquals("region", filtered.getDatasets().get(0).getFields().get(0).getName());

        assertEquals(1, filtered.getMetrics().size());
        assertEquals("revenue", filtered.getMetrics().get(0).getName());

        // The dataset itself is never dropped — dataset-level HIDE isn't in scope yet.
        assertEquals("customers", filtered.getDatasets().get(0).getName());
    }

    @Test
    public void filterHiddenKeepsEverythingForMatchingRole() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("customers");
        OssieModelDto.Field ssn = new OssieModelDto.Field();
        ssn.setName("ssn");
        ssn.setAllowRoles(List.of("ROLE_ADMIN"));
        ds.getFields().add(ssn);
        semantic.getDatasets().add(ds);

        OssieModelDto filtered = OssieRoleContext.filterHidden(semantic, Set.of("ROLE_ADMIN"));
        assertEquals(1, filtered.getDatasets().get(0).getFields().size());
    }

    @Test
    public void filterHiddenWithNullRolesBehavesLikeEmptySet() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Metric revenue = new OssieModelDto.Metric();
        revenue.setName("revenue");
        semantic.getMetrics().add(revenue);

        OssieModelDto filtered = OssieRoleContext.filterHidden(semantic, null);
        assertFalse(filtered.getMetrics().isEmpty());
    }
}
