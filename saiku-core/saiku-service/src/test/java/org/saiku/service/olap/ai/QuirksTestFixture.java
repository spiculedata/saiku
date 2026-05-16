/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Reusable in-process test fixture covering the schema-shape quirks
 * documented during the AI-query live-fuzz session (saiku#807-811).
 *
 * <p>This is the "quirks cube" the platform-improvements plan called for
 * (Phase 3 #7). Instead of a separate Mondrian XML + H2 data jar, the
 * fixture lives at the {@link OlapDiscoverService} layer — the
 * downstream AI metadata service + converter are pure with respect to
 * the discover interface, so a stubbed discover service is enough to
 * exercise every code path that matters for property-based testing
 * (Phase 3.C) and snapshot tests (Phase 3.B).
 *
 * <p>The fixture exposes one cube, {@code Quirks}, with these
 * intentional oddities:
 *
 * <ul>
 *   <li><b>hasAll=false hierarchy</b> — {@code Time/Time} with levels
 *       {@code [Year, Quarter]} and no {@code (All)} level. Triggers the
 *       segments→depth offset path closed in saiku#807.</li>
 *   <li><b>Standard (All)-rooted hierarchy</b> (control) —
 *       {@code Customer/Customers} with {@code [(All), Country, State]}.</li>
 *   <li><b>Numeric-keyed level</b> — {@code Employee/Salary} with
 *       members like {@code "20.0"}, {@code "4400.0"}. Triggers the
 *       TopCount-ordering quirk documented in saiku#809.</li>
 *   <li><b>Parent-child closure hierarchy</b> —
 *       {@code Employee/Employee$Manager Id$Parent} with levels
 *       {@code [(All), Closure, Item]}. Listed in /schema but unqueryable
 *       through the AI surface (saiku#810).</li>
 *   <li><b>Same-dim multi-hierarchy</b> — {@code Customer/Gender} +
 *       {@code Customer/Marital Status} (both single-level). Triggers
 *       the same-dim different-hier rules pinned at saiku#784 scope.</li>
 *   <li><b>Virtual-cube dim alias</b> — {@code Store2} resolves to
 *       the real Store dim. Matches the FoodMart Warehouse-and-Sales
 *       shape from the live-fuzz session.</li>
 * </ul>
 *
 * <p>Test classes typically use one of:
 * <ul>
 *   <li>{@link #discover()} — a {@link StubDiscover} subclass injectable
 *       into {@link OlapAiCubeMetadataService#setDiscoverService};</li>
 *   <li>{@link #cubeRef()} — the canonical {@link AiCubeRef} for the
 *       Quirks cube;</li>
 *   <li>{@link #directSchema()} — a pre-built {@link AiSchema} for
 *       converter-only tests that don't go through the metadata service.</li>
 * </ul>
 */
public final class QuirksTestFixture {

    public static final String CONNECTION = "quirks_conn";
    public static final String CATALOG = "QuirksCatalog";
    public static final String SCHEMA = "QuirksSchema";
    public static final String CUBE = "Quirks";

    private QuirksTestFixture() {}

    /** Canonical cube ref for the Quirks cube. */
    public static AiCubeRef cubeRef() {
        return new AiCubeRef(CONNECTION, CATALOG, SCHEMA, CUBE);
    }

    /** Stubbed OlapDiscoverService that yields the Quirks cube. Inject
     *  into {@link OlapAiCubeMetadataService#setDiscoverService}. */
    public static StubDiscover discover() {
        return new StubDiscover();
    }

    /** Build a converter-ready AiSchema directly (no metadata service).
     *  Used by AiSchemaConverter unit tests that exercise quirks without
     *  the full service layer. canonicalCube is populated, matching the
     *  saiku#811-fixed metadata-service behaviour. */
    public static AiSchema directSchema() {
        AiCubeRef canonical = cubeRef();
        AiSchema s = new AiSchema(
                CONNECTION + "/" + CATALOG + "/" + SCHEMA + "/" + CUBE,
                CUBE,
                "[" + CONNECTION + "].[" + CATALOG + "].[" + SCHEMA + "].[" + CUBE + "]");
        s.canonicalCube = canonical;

        s.measures.put(AiSchema.key("Unit Sales"), new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]"));
        s.measures.put(
                AiSchema.key("Number of Employees"),
                new AiSchema.Measure("Number of Employees", "[Measures].[Number of Employees]"));

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeHier = new AiSchema.Hierarchy("Time", "[Time].[Time]");
        timeHier.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", "[Time].[Time].[Year]"));
        timeHier.levels.put(AiSchema.key("Quarter"), new AiSchema.Level("Quarter", "[Time].[Time].[Quarter]"));
        time.hierarchies.put(AiSchema.key("Time"), timeHier);
        s.dimensions.put(AiSchema.key("Time"), time);

        AiSchema.Dimension cust = new AiSchema.Dimension("Customer", "[Customer]");

        AiSchema.Hierarchy customers = new AiSchema.Hierarchy("Customers", "[Customer].[Customers]");
        customers.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Customer].[Customers].[(All)]"));
        customers.levels.put(
                AiSchema.key("Country"), new AiSchema.Level("Country", "[Customer].[Customers].[Country]"));
        customers.levels.put(AiSchema.key("State"), new AiSchema.Level("State", "[Customer].[Customers].[State]"));
        cust.hierarchies.put(AiSchema.key("Customers"), customers);

        AiSchema.Hierarchy gender = new AiSchema.Hierarchy("Gender", "[Customer].[Gender]");
        gender.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Customer].[Gender].[(All)]"));
        gender.levels.put(AiSchema.key("Gender"), new AiSchema.Level("Gender", "[Customer].[Gender].[Gender]"));
        cust.hierarchies.put(AiSchema.key("Gender"), gender);

        AiSchema.Hierarchy marital = new AiSchema.Hierarchy("Marital Status", "[Customer].[Marital Status]");
        marital.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Customer].[Marital Status].[(All)]"));
        marital.levels.put(
                AiSchema.key("Marital Status"),
                new AiSchema.Level("Marital Status", "[Customer].[Marital Status].[Marital Status]"));
        cust.hierarchies.put(AiSchema.key("Marital Status"), marital);

        s.dimensions.put(AiSchema.key("Customer"), cust);

        AiSchema.Dimension emp = new AiSchema.Dimension("Employee", "[Employee]");

        AiSchema.Hierarchy salary = new AiSchema.Hierarchy("Salary", "[Employee].[Salary]");
        salary.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Employee].[Salary].[(All)]"));
        salary.levels.put(AiSchema.key("Salary"), new AiSchema.Level("Salary", "[Employee].[Salary].[Salary]"));
        emp.hierarchies.put(AiSchema.key("Salary"), salary);

        AiSchema.Hierarchy closure =
                new AiSchema.Hierarchy("Employee$Manager Id$Parent", "[Employee].[Employee$Manager Id$Parent]");
        closure.levels.put(
                AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Employee].[Employee$Manager Id$Parent].[(All)]"));
        closure.levels.put(
                AiSchema.key("Closure"),
                new AiSchema.Level("Closure", "[Employee].[Employee$Manager Id$Parent].[Closure]"));
        closure.levels.put(
                AiSchema.key("Item"), new AiSchema.Level("Item", "[Employee].[Employee$Manager Id$Parent].[Item]"));
        emp.hierarchies.put(AiSchema.key("Employee$Manager Id$Parent"), closure);

        s.dimensions.put(AiSchema.key("Employee"), emp);

        AiSchema.Dimension store2 = new AiSchema.Dimension("Store2", "[Store2]");
        AiSchema.Hierarchy storeType = new AiSchema.Hierarchy("Store Type", "[Store2].[Store Type]");
        storeType.levels.put(AiSchema.key("(All)"), new AiSchema.Level("(All)", "[Store2].[Store Type].[(All)]"));
        storeType.levels.put(
                AiSchema.key("Store Type"), new AiSchema.Level("Store Type", "[Store2].[Store Type].[Store Type]"));
        store2.hierarchies.put(AiSchema.key("Store Type"), storeType);
        s.dimensions.put(AiSchema.key("Store2"), store2);

        return s;
    }

    /**
     * StubDiscover for the Quirks cube. Subclasses can override
     * {@code getLevelMembers} to control probe outcomes (saiku#810 prune
     * tests already use this pattern in OlapAiCubeMetadataServiceTest).
     */
    public static class StubDiscover extends OlapDiscoverService {

        @Override
        public List<SaikuCube> getAllCubes() throws SaikuOlapException {
            return List.of(quirksCube());
        }

        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            return Arrays.asList(measure("Unit Sales"), measure("Number of Employees"));
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) throws SaikuServiceException {
            return Arrays.asList(
                    new SaikuDimension("Measures", "[Measures]", "Measures", "", true, new ArrayList<>()),
                    timeDim(),
                    customerDim(),
                    employeeDim(),
                    store2Dim());
        }

        @Override
        public List<SaikuHierarchy> getAllDimensionHierarchies(SaikuCube cube, String dimensionName) {
            switch (dimensionName) {
                case "Time":
                    return List.of(timeHier());
                case "Customer":
                    return Arrays.asList(customersHier(), genderHier(), maritalHier());
                case "Employee":
                    return Arrays.asList(salaryHier(), closureHier());
                case "Store2":
                    return List.of(storeTypeHier());
                default:
                    return new ArrayList<>();
            }
        }

        @Override
        public List<SaikuLevel> getAllHierarchyLevels(SaikuCube cube, String dimensionName, String hierarchyName) {
            switch (hierarchyName) {
                case "Time":
                    return timeHier().getLevels();
                case "Customers":
                    return customersHier().getLevels();
                case "Gender":
                    return genderHier().getLevels();
                case "Marital Status":
                    return maritalHier().getLevels();
                case "Salary":
                    return salaryHier().getLevels();
                case "Employee$Manager Id$Parent":
                    return closureHier().getLevels();
                case "Store Type":
                    return storeTypeHier().getLevels();
                default:
                    return new ArrayList<>();
            }
        }

        /**
         * Default member-fetch behaviour: returns empty (sample-member
         * fetch tolerates this — see populateSampleMembers in
         * OlapAiCubeMetadataService). The saiku#810 probe path uses this
         * too; subclasses can override to fail specific levels.
         */
        @Override
        public List<SimpleCubeElement> getLevelMembers(
                SaikuCube cube, String hierarchyName, String levelName, String q, int limit) {
            return new ArrayList<>();
        }

        /* ---------- helper builders ---------- */

        private SaikuCube quirksCube() {
            return new SaikuCube(
                    CONNECTION,
                    "[" + CONNECTION + "].[" + CATALOG + "].[" + SCHEMA + "].[" + CUBE + "]",
                    CUBE,
                    CUBE,
                    CATALOG,
                    SCHEMA);
        }

        private SaikuMember measure(String name) {
            return new SaikuMember(
                    name,
                    "[Measures].[" + name + "]",
                    name,
                    "",
                    "[Measures]",
                    "[Measures].[MeasuresLevel]",
                    "[Measures].[MeasuresLevel]");
        }

        private SaikuDimension timeDim() {
            return new SaikuDimension("Time", "[Time]", "Time", "", true, List.of(timeHier()));
        }

        private SaikuDimension customerDim() {
            return new SaikuDimension(
                    "Customer",
                    "[Customer]",
                    "Customer",
                    "",
                    true,
                    Arrays.asList(customersHier(), genderHier(), maritalHier()));
        }

        private SaikuDimension employeeDim() {
            return new SaikuDimension(
                    "Employee", "[Employee]", "Employee", "", true, Arrays.asList(salaryHier(), closureHier()));
        }

        private SaikuDimension store2Dim() {
            return new SaikuDimension("Store2", "[Store2]", "Store2", "", true, List.of(storeTypeHier()));
        }

        private SaikuHierarchy timeHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("Year", "[Time].[Time].[Year]", "[Time]", "[Time].[Time]"),
                    level("Quarter", "[Time].[Time].[Quarter]", "[Time]", "[Time].[Time]"));
            return new SaikuHierarchy("Time", "[Time].[Time]", "Time", "", "[Time]", true, levels, new ArrayList<>());
        }

        private SaikuHierarchy customersHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("(All)", "[Customer].[Customers].[(All)]", "[Customer]", "[Customer].[Customers]"),
                    level("Country", "[Customer].[Customers].[Country]", "[Customer]", "[Customer].[Customers]"),
                    level("State", "[Customer].[Customers].[State]", "[Customer]", "[Customer].[Customers]"));
            return new SaikuHierarchy(
                    "Customers",
                    "[Customer].[Customers]",
                    "Customers",
                    "",
                    "[Customer]",
                    true,
                    levels,
                    new ArrayList<>());
        }

        private SaikuHierarchy genderHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("(All)", "[Customer].[Gender].[(All)]", "[Customer]", "[Customer].[Gender]"),
                    level("Gender", "[Customer].[Gender].[Gender]", "[Customer]", "[Customer].[Gender]"));
            return new SaikuHierarchy(
                    "Gender", "[Customer].[Gender]", "Gender", "", "[Customer]", true, levels, new ArrayList<>());
        }

        private SaikuHierarchy maritalHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("(All)", "[Customer].[Marital Status].[(All)]", "[Customer]", "[Customer].[Marital Status]"),
                    level(
                            "Marital Status",
                            "[Customer].[Marital Status].[Marital Status]",
                            "[Customer]",
                            "[Customer].[Marital Status]"));
            return new SaikuHierarchy(
                    "Marital Status",
                    "[Customer].[Marital Status]",
                    "Marital Status",
                    "",
                    "[Customer]",
                    true,
                    levels,
                    new ArrayList<>());
        }

        private SaikuHierarchy salaryHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("(All)", "[Employee].[Salary].[(All)]", "[Employee]", "[Employee].[Salary]"),
                    level("Salary", "[Employee].[Salary].[Salary]", "[Employee]", "[Employee].[Salary]"));
            return new SaikuHierarchy(
                    "Salary", "[Employee].[Salary]", "Salary", "", "[Employee]", true, levels, new ArrayList<>());
        }

        private SaikuHierarchy closureHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level(
                            "(All)",
                            "[Employee].[Employee$Manager Id$Parent].[(All)]",
                            "[Employee]",
                            "[Employee].[Employee$Manager Id$Parent]"),
                    level(
                            "Closure",
                            "[Employee].[Employee$Manager Id$Parent].[Closure]",
                            "[Employee]",
                            "[Employee].[Employee$Manager Id$Parent]"),
                    level(
                            "Item",
                            "[Employee].[Employee$Manager Id$Parent].[Item]",
                            "[Employee]",
                            "[Employee].[Employee$Manager Id$Parent]"));
            return new SaikuHierarchy(
                    "Employee$Manager Id$Parent",
                    "[Employee].[Employee$Manager Id$Parent]",
                    "Employee$Manager Id$Parent",
                    "",
                    "[Employee]",
                    true,
                    levels,
                    new ArrayList<>());
        }

        private SaikuHierarchy storeTypeHier() {
            List<SaikuLevel> levels = Arrays.asList(
                    level("(All)", "[Store2].[Store Type].[(All)]", "[Store2]", "[Store2].[Store Type]"),
                    level("Store Type", "[Store2].[Store Type].[Store Type]", "[Store2]", "[Store2].[Store Type]"));
            return new SaikuHierarchy(
                    "Store Type",
                    "[Store2].[Store Type]",
                    "Store Type",
                    "",
                    "[Store2]",
                    true,
                    levels,
                    new ArrayList<>());
        }

        private SaikuLevel level(String name, String uniqueName, String dimUniq, String hierUniq) {
            return new SaikuLevel(name, uniqueName, name, "", dimUniq, hierUniq, true, "Regular", new HashMap<>());
        }
    }
}
