/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuValidationException;

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
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("customers");
        semantic.getDatasets().add(ds);
        OssieQueryModel model = new OssieQueryModel();
        model.setFactDataset("customers");
        OssieRoleContext.resolve(Set.of(), semantic).assertShelfVisible(model); // no exception
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

    @Test
    public void combinedRowPredicateForOrsMatchingRolesAndWrapsThem() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("geography");
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_APAC", "REGION = 'APAC'"));
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_EMEA", "REGION = 'EMEA'"));
        semantic.getDatasets().add(ds);

        assertEquals(
                "REGION = 'APAC'",
                OssieRoleContext.resolve(Set.of("ROLE_APAC"), semantic).combinedRowPredicateFor("geography"));
        assertEquals(
                "(REGION = 'APAC' OR REGION = 'EMEA')",
                OssieRoleContext.resolve(Set.of("ROLE_APAC", "ROLE_EMEA"), semantic)
                        .combinedRowPredicateFor("geography"));
    }

    @Test
    public void combinedRowPredicateForIsNullWhenNothingApplies() {
        OssieModelDto semantic = new OssieModelDto();
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("geography");
        ds.getRowPredicates().add(new OssieModelDto.RowPredicate("ROLE_APAC", "REGION = 'APAC'"));
        semantic.getDatasets().add(ds);

        assertNull(OssieRoleContext.resolve(Set.of("ROLE_OTHER"), semantic).combinedRowPredicateFor("geography"));
        assertNull(OssieRoleContext.resolve(Set.of("ROLE_APAC"), semantic).combinedRowPredicateFor("nope"));
    }

    // ---- saiku#2526: unresolvable identifiers are typed rejections, never raw columns ----

    private static OssieModelDto semanticWithEmailField() {
        OssieModelDto semantic = new OssieModelDto();
        semantic.setName("TPCDS");
        OssieModelDto.Dataset ds = new OssieModelDto.Dataset();
        ds.setName("customer");
        OssieModelDto.Field email = new OssieModelDto.Field();
        email.setName("c_email");
        email.setAllowRoles(List.of("ROLE_ADMIN"));
        OssieModelDto.Field lastName = new OssieModelDto.Field();
        lastName.setName("c_last_name");
        ds.getFields().add(email);
        ds.getFields().add(lastName);
        semantic.getDatasets().add(ds);
        return semantic;
    }

    private static OssieQueryModel modelWithRows(OssieQueryModel.FieldRef... rows) {
        OssieQueryModel m = new OssieQueryModel();
        m.setFactDataset("customer");
        m.setRows(List.of(rows));
        return m;
    }

    @Test
    public void physicalColumnNameInRowsIsAValidationError() {
        // c_email is denied; C_EMAIL_ADDRESS is its physical column — naming it must not read
        // the values through the translator's old raw-column fall-through.
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_USER"), semanticWithEmailField());
        OssieQueryModel.FieldRef ref = new OssieQueryModel.FieldRef();
        ref.setDataset("customer");
        ref.setField("C_EMAIL_ADDRESS");
        try {
            ctx.assertShelfVisible(modelWithRows(ref));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("C_EMAIL_ADDRESS"));
        }
    }

    @Test
    public void unknownFieldInRowsIsAValidationErrorForEveryCaller() {
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of(), semanticWithEmailField());
        OssieQueryModel.FieldRef ref = new OssieQueryModel.FieldRef();
        ref.setDataset("customer");
        ref.setField("not_a_field");
        try {
            ctx.assertShelfVisible(modelWithRows(ref));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not_a_field"));
        }
    }

    @Test
    public void unknownDatasetInRowsIsAValidationError() {
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of(), semanticWithEmailField());
        OssieQueryModel.FieldRef ref = new OssieQueryModel.FieldRef();
        ref.setDataset("no_such_dataset");
        ref.setField("c_last_name");
        try {
            ctx.assertShelfVisible(modelWithRows(ref));
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no_such_dataset"));
        }
    }

    @Test
    public void filterWithoutDatasetIsResolvedAgainstTheModel() {
        // A filter naming no dataset used to be skipped entirely by the role check.
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_USER"), semanticWithEmailField());
        OssieQueryModel m = modelWithRows(row("customer", "c_last_name"));
        OssieQueryModel.FilterExpr f = new OssieQueryModel.FilterExpr();
        f.setField("C_EMAIL_ADDRESS"); // physical column, no dataset
        f.setOp("EQ");
        f.setValue("alice@example.com");
        m.setFilters(List.of(f));
        try {
            ctx.assertShelfVisible(m);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("C_EMAIL_ADDRESS"));
        }

        // Resolving under the semantic name but denied is an access denial, not a pass.
        OssieQueryModel denied = modelWithRows(row("customer", "c_last_name"));
        OssieQueryModel.FilterExpr deniedFilter = new OssieQueryModel.FilterExpr();
        deniedFilter.setField("c_email");
        deniedFilter.setOp("EQ");
        deniedFilter.setValue("alice@example.com");
        denied.setFilters(List.of(deniedFilter));
        try {
            ctx.assertShelfVisible(denied);
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("c_email"));
        }
    }

    @Test
    public void sortMetricMustResolveAndBeVisible() {
        // sorts[].metric was never checked — by name or by visibility.
        OssieModelDto semantic = semanticWithEmailField();
        OssieModelDto.Metric restricted = new OssieModelDto.Metric();
        restricted.setName("exec_revenue");
        restricted.setAllowRoles(List.of("ROLE_ADMIN"));
        semantic.getMetrics().add(restricted);
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of("ROLE_USER"), semantic);

        OssieQueryModel unknown = modelWithRows(row("customer", "c_last_name"));
        OssieQueryModel.SortRef unknownSort = new OssieQueryModel.SortRef();
        unknownSort.setMetric("not_a_metric");
        unknown.setSorts(List.of(unknownSort));
        try {
            ctx.assertShelfVisible(unknown);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not_a_metric"));
        }

        OssieQueryModel denied = modelWithRows(row("customer", "c_last_name"));
        OssieQueryModel.SortRef deniedSort = new OssieQueryModel.SortRef();
        deniedSort.setMetric("exec_revenue");
        denied.setSorts(List.of(deniedSort));
        try {
            ctx.assertShelfVisible(denied);
            fail("expected SaikuAccessDeniedException");
        } catch (SaikuAccessDeniedException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exec_revenue"));
        }
    }

    @Test
    public void unknownMetricInValuesIsAValidationError() {
        OssieRoleContext ctx = OssieRoleContext.resolve(Set.of(), semanticWithEmailField());
        OssieQueryModel m = modelWithRows(row("customer", "c_last_name"));
        OssieQueryModel.MetricRef v = new OssieQueryModel.MetricRef();
        v.setMetric("not_a_metric");
        m.setValues(List.of(v));
        try {
            ctx.assertShelfVisible(m);
            fail("expected SaikuValidationException");
        } catch (SaikuValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not_a_metric"));
        }
    }

    private static OssieQueryModel.FieldRef row(String dataset, String field) {
        OssieQueryModel.FieldRef ref = new OssieQueryModel.FieldRef();
        ref.setDataset(dataset);
        ref.setField(field);
        return ref;
    }
}
