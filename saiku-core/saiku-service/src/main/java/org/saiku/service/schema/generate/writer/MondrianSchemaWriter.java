package org.saiku.service.schema.generate.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mondrian.olap.MondrianDef;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftJoin;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Emits Mondrian 4 schema XML from a {@link DraftSchema} by building the Mondrian object graph and
 * delegating to {@link MondrianDef.Schema#toXML()}.
 *
 * <p>Target format is Mondrian 4.x ({@code metamodelVersion="4.0"}) because that is what Saiku
 * runs on (resolved {@code pentaho:mondrian:4.8.1.0-SAIKU-jakarta}). The 3.x shape described in
 * the original plan (Cube → Measure, DimensionUsage, inline Hierarchy) does not exist in the 4.x
 * API surface we depend on, so this writer emits:
 *
 * <ul>
 *   <li>A {@code PhysicalSchema} block with a {@code Table} for each distinct fact / dimension
 *       table (dim tables carry a {@code Key} derived from {@link DraftHierarchy#primaryKey()})
 *       and a {@code Link} per cube-scoped dimension FK. Fact tables that host a degenerate TIME
 *       dimension also carry a {@code ColumnDefs} holder with a {@code CalculatedColumnDef} per
 *       Y/Q/M/D level (e.g. {@code CalculatedColumnDef name="order_date_year"} whose expression
 *       is {@code YEAR(order_date)}). Those calc columns become the {@code keyColumn} targets of
 *       the TIME dim's attributes — Mondrian resolves the expression through its dialect layer.
 *   <li>Shared dimensions as top-level {@code Dimension} elements, each with an {@code Attributes}
 *       holder (one {@code Attribute} per level) and a {@code Hierarchies} holder (one {@code
 *       Hierarchy} per draft hierarchy with {@code Level} elements referencing the attributes).
 *   <li>Each cube with its private dimensions in a {@code Dimensions} holder, a single {@code
 *       MeasureGroup} whose {@code Measures} holder contains one {@code Measure} per draft
 *       measure, and {@code DimensionLinks} containing a {@code ForeignKeyLink} per non-TIME
 *       dimension. Degenerate TIME dims emit no link — they colocate on the fact row.
 * </ul>
 *
 * <p>Known simplifications (tracked for future tasks, not blockers for A9):
 *
 * <ul>
 *   <li>Only one-hop snowflakes are emitted (the shape {@link
 *       org.saiku.service.schema.generate.infer.DimensionBuilder} detects). Multi-hop lookups are
 *       not recursed — each snowflake dim adds exactly one lookup {@code Table} + one {@code Link}.
 *   <li>Provenance is not emitted as comments; Mondrian 4's XOM writer does not preserve
 *       free-floating comments, so tracking this belongs in a later provenance-specific task.
 * </ul>
 */
public class MondrianSchemaWriter {

    public String write(DraftSchema draft) {
        if (draft == null) {
            throw new IllegalArgumentException("draft is null");
        }
        MondrianDef.Schema mSchema = convert(draft);
        return mSchema.toXML();
    }

    /**
     * Emit both the Mondrian XML and the canonical {@code <schemaName>.generated.json} sidecar in
     * one call — the production save path wants both atomically. The XML is identical to
     * {@link #write(DraftSchema)}; the sidecar embeds the draft + op log via {@link
     * GeneratedSidecarIo}.
     */
    public WriteResult writeWithSidecar(DraftSchema draft, List<SuggestionOp> opLog) {
        String xml = write(draft);
        GeneratedSidecar sidecar =
                GeneratedSidecarIo.build(draft, opLog == null ? List.of() : opLog, draft.name(), null);
        return new WriteResult(xml, GeneratedSidecarIo.write(sidecar));
    }

    /** Result pair: Mondrian schema XML + sidecar JSON. */
    public record WriteResult(String xml, String sidecarJson) {}

    // --- conversion -------------------------------------------------------

    private MondrianDef.Schema convert(DraftSchema draft) {
        MondrianDef.Schema mSchema = new MondrianDef.Schema();
        mSchema.name = draft.name();
        mSchema.metamodelVersion = "4.0";
        mSchema.missingLink = "warning"; // default, set explicitly for clarity in output

        List<MondrianDef.SchemaElement> children = new ArrayList<>();

        // 1. PhysicalSchema — gather all tables + FK links.
        children.add(buildPhysicalSchema(draft));

        // 2. Shared dimensions.
        for (DraftDimension shared : draft.sharedDimensions()) {
            children.add(buildDimension(shared, /* asSharedChildOfSchema= */ true));
        }

        // 3. Cubes.
        for (DraftCube cube : draft.cubes()) {
            children.add(buildCube(cube));
        }

        mSchema.childArray = children.toArray(new MondrianDef.SchemaElement[0]);
        return mSchema;
    }

    private MondrianDef.PhysicalSchema buildPhysicalSchema(DraftSchema draft) {
        MondrianDef.PhysicalSchema phys = new MondrianDef.PhysicalSchema();

        // Collect distinct tables, dim-table primary keys, and fact→dim FK links.
        Set<String> factTables = new LinkedHashSet<>();
        // dimTable -> primaryKey column (first seen wins)
        Map<String, String> dimTables = new LinkedHashMap<>();
        // factTable -> list of (calcColumnName, expressionSql) for degenerate TIME dims.
        Map<String, List<CalcCol>> factCalcCols = new LinkedHashMap<>();
        // Snowflake lookup tables: lookupTable -> primaryKey column on the lookup side.
        Map<String, String> snowflakeLookupTables = new LinkedHashMap<>();
        // Snowflake links, keyed by (source=lookup, target=dim) to dedupe.
        Map<String, DraftJoin> snowflakeLinks = new LinkedHashMap<>();

        for (DraftCube cube : draft.cubes()) {
            if (cube.sourceFactTable() != null) {
                factTables.add(cube.sourceFactTable());
            }
            for (DraftDimension d : cube.dimensions()) {
                if (d.type() == DraftDimension.Type.TIME) {
                    // Degenerate TIME dim: colocated on the fact. Collect calc columns; do NOT
                    // add to dimTables (that would emit a duplicate <Table> and a bogus Key).
                    String host = d.sourceTable() != null ? d.sourceTable() : cube.sourceFactTable();
                    if (host != null) {
                        List<CalcCol> list = factCalcCols.computeIfAbsent(host, k -> new ArrayList<>());
                        addCalcCols(list, d);
                    }
                    continue;
                }
                if (d.sourceTable() == null) {
                    continue;
                }
                String pk = primaryKeyOf(d);
                dimTables.putIfAbsent(d.sourceTable(), pk);
                collectSnowflake(d, snowflakeLookupTables, snowflakeLinks);
            }
        }
        // Shared dims (non-TIME).
        for (DraftDimension d : draft.sharedDimensions()) {
            if (d.type() == DraftDimension.Type.TIME) {
                // Schema-scope shared TIME dims are no longer emitted by the inferrer; if one
                // arrives via an external draft, skip it from physical layout rather than emitting
                // a phantom table.
                continue;
            }
            if (d.sourceTable() == null) {
                continue;
            }
            dimTables.putIfAbsent(d.sourceTable(), primaryKeyOf(d));
            collectSnowflake(d, snowflakeLookupTables, snowflakeLinks);
        }

        List<MondrianDef.PhysicalSchemaElement> elements = new ArrayList<>();

        for (String fact : factTables) {
            elements.add(factTable(fact, factCalcCols.get(fact)));
        }
        for (Map.Entry<String, String> e : dimTables.entrySet()) {
            elements.add(dimTable(e.getKey(), e.getValue()));
        }
        // Snowflake lookup tables. Skip any already registered as a primary dim sourceTable —
        // one PhysicalSchema Table element per physical table, even if multiple dims use it.
        for (Map.Entry<String, String> e : snowflakeLookupTables.entrySet()) {
            if (dimTables.containsKey(e.getKey()) || factTables.contains(e.getKey())) {
                continue;
            }
            elements.add(dimTable(e.getKey(), e.getValue()));
        }

        // Snowflake Links — source = lookup (PK-side), target = dim sourceTable (FK-side).
        // Matches the cube-dim Link convention Mondrian expects.
        for (DraftJoin join : snowflakeLinks.values()) {
            MondrianDef.Link link = new MondrianDef.Link();
            link.source = join.rightTable();
            link.target = join.leftTable();
            MondrianDef.ForeignKey fk = new MondrianDef.ForeignKey();
            fk.array = new MondrianDef.Column[] {new MondrianDef.Column(null, join.leftKey())};
            link.foreignKey = fk;
            elements.add(link);
        }

        // Fact → dim Links (one per cube-scoped dimension with a foreign key).
        // Degenerate TIME dims have no FK (they live on the fact) and are skipped here.
        for (DraftCube cube : draft.cubes()) {
            if (cube.sourceFactTable() == null) {
                continue;
            }
            for (DraftDimension d : cube.dimensions()) {
                if (d.type() == DraftDimension.Type.TIME) {
                    continue;
                }
                if (d.sourceTable() == null || d.foreignKey() == null) {
                    continue;
                }
                // Mondrian 4 Link semantics: source = table with the primary key (dim),
                // target = table with the foreign key column (fact). Flipping these triggers
                // "source table X of link has no key named 'primary'" at RolapSchemaLoader.
                MondrianDef.Link link = new MondrianDef.Link();
                link.source = d.sourceTable();
                link.target = cube.sourceFactTable();
                MondrianDef.ForeignKey fk = new MondrianDef.ForeignKey();
                fk.array = new MondrianDef.Column[] {new MondrianDef.Column(null, d.foreignKey())};
                link.foreignKey = fk;
                elements.add(link);
            }
        }

        phys.childArray = elements.toArray(new MondrianDef.PhysicalSchemaElement[0]);
        return phys;
    }

    /**
     * Emit a fact table with optional {@code ColumnDefs} carrying the Y/Q/M/D
     * {@link MondrianDef.CalculatedColumnDef}s for degenerate TIME dims. The fact has no Key
     * attribute — cubes bind to it by name alone through {@code MeasureGroup.table}.
     */
    private MondrianDef.Table factTable(String name, List<CalcCol> calcCols) {
        MondrianDef.Table t = new MondrianDef.Table();
        t.name = name;
        if (calcCols == null || calcCols.isEmpty()) {
            t.childArray = new MondrianDef.TableElement[0];
            return t;
        }
        MondrianDef.ColumnDefs defs = new MondrianDef.ColumnDefs();
        List<MondrianDef.RealOrCalcColumnDef> list = new ArrayList<>();
        // Deduplicate by calc-column name (multiple date columns → distinct prefixes, but guard
        // in case of an exotic case).
        Set<String> seen = new LinkedHashSet<>();
        for (CalcCol cc : calcCols) {
            if (!seen.add(cc.name)) {
                continue;
            }
            list.add(calculatedColumnDef(cc.name, cc.expression));
        }
        defs.array = list.toArray(new MondrianDef.RealOrCalcColumnDef[0]);
        t.childArray = new MondrianDef.TableElement[] {defs};
        return t;
    }

    private MondrianDef.CalculatedColumnDef calculatedColumnDef(String name, String sqlExpr) {
        MondrianDef.CalculatedColumnDef def = new MondrianDef.CalculatedColumnDef();
        def.name = name;
        def.type = "Integer";
        MondrianDef.ExpressionView view = new MondrianDef.ExpressionView();
        MondrianDef.SQL sql = new MondrianDef.SQL();
        sql.dialect = "generic";
        sql.setCData(sqlExpr);
        view.expressions = new MondrianDef.SQL[] {sql};
        def.expression = view;
        return def;
    }

    private MondrianDef.Table dimTable(String name, String keyColumn) {
        MondrianDef.Table t = new MondrianDef.Table();
        t.name = name;
        if (keyColumn != null) {
            MondrianDef.Key key = new MondrianDef.Key();
            key.array = new MondrianDef.Column[] {new MondrianDef.Column(null, keyColumn)};
            t.childArray = new MondrianDef.TableElement[] {key};
        } else {
            t.childArray = new MondrianDef.TableElement[0];
        }
        return t;
    }

    /**
     * Scan a dimension's hierarchies for {@link DraftJoin} snowflake edges and register the lookup
     * table + link with the given accumulators. Dedupes by lookup-table name (first hit wins for
     * PK choice) and by (lookup, dim-source) pair for links.
     */
    private void collectSnowflake(DraftDimension d, Map<String, String> lookupTables, Map<String, DraftJoin> links) {
        for (DraftHierarchy h : d.hierarchies()) {
            DraftJoin join = h.join();
            if (join == null || join.rightTable() == null) {
                continue;
            }
            lookupTables.putIfAbsent(join.rightTable(), join.rightKey());
            String linkKey = join.rightTable() + "->" + join.leftTable();
            links.putIfAbsent(linkKey, join);
        }
    }

    private String primaryKeyOf(DraftDimension d) {
        if (d.hierarchies().isEmpty()) {
            return null;
        }
        return d.hierarchies().get(0).primaryKey();
    }

    /** Build a Mondrian Dimension from a draft dim. */
    private MondrianDef.Dimension buildDimension(DraftDimension d, boolean asSharedChildOfSchema) {
        if (d.type() == DraftDimension.Type.TIME) {
            return buildTimeDimension(d);
        }
        return buildStandardDimension(d);
    }

    private MondrianDef.Dimension buildStandardDimension(DraftDimension d) {
        MondrianDef.Dimension md = new MondrianDef.Dimension();
        md.name = d.name();
        if (d.sourceTable() != null) {
            md.table = d.sourceTable();
        }

        // The dimension needs a key attribute name. Derive one from the hierarchy's primary key
        // column, upper-casing the first letter (Mondrian 4 requires a key attribute by name).
        String keyAttrName = keyAttributeName(d);
        if (keyAttrName != null) {
            md.key = keyAttrName;
        }

        List<MondrianDef.DimensionElement> children = new ArrayList<>();

        MondrianDef.Attributes attrs = new MondrianDef.Attributes();
        List<MondrianDef.Attribute> attrList = new ArrayList<>();

        if (keyAttrName != null) {
            MondrianDef.Attribute keyAttr = new MondrianDef.Attribute();
            keyAttr.name = keyAttrName;
            keyAttr.keyColumn = primaryKeyOf(d);
            keyAttr.hasHierarchy = Boolean.FALSE;
            attrList.add(keyAttr);
        }

        for (DraftHierarchy h : d.hierarchies()) {
            for (DraftLevel l : h.levels()) {
                MondrianDef.Attribute a = new MondrianDef.Attribute();
                a.name = l.name();
                a.keyColumn = l.column();
                // nameColumn is the human-readable caption source. MondrianDef.Attribute
                // exposes it as a plain String attribute (verified against
                // mondrian-4.8.1.0-SAIKU-jakarta sources), so we emit it directly.
                if (l.nameColumn() != null) {
                    a.nameColumn = l.nameColumn();
                }
                a.levelType = levelType(l.type());
                a.hasHierarchy = Boolean.FALSE;
                // Snowflake-side levels carry an explicit physical table so Mondrian routes
                // the column to the right table across the Link. Levels without a table fall
                // back to the dimension's primary sourceTable (default behaviour).
                if (l.table() != null) {
                    a.table = l.table();
                }
                attrList.add(a);
            }
        }
        attrs.array = attrList.toArray(new MondrianDef.Attribute[0]);
        children.add(attrs);

        children.add(buildHierarchiesHolder(d));

        md.childArray = children.toArray(new MondrianDef.DimensionElement[0]);
        return md;
    }

    /**
     * Build a degenerate TIME dimension that colocates on the fact table. Attributes key on
     * synthetic calc columns (e.g. {@code order_date_year}) emitted into the fact's physical
     * {@code ColumnDefs} by {@link #factTable(String, List)}. The finest-granularity attribute
     * (Day) doubles as the dimension key.
     */
    private MondrianDef.Dimension buildTimeDimension(DraftDimension d) {
        MondrianDef.Dimension md = new MondrianDef.Dimension();
        md.name = d.name();
        md.type = "TIME";
        if (d.sourceTable() != null) {
            md.table = d.sourceTable();
        }

        List<MondrianDef.DimensionElement> children = new ArrayList<>();

        MondrianDef.Attributes attrs = new MondrianDef.Attributes();
        List<MondrianDef.Attribute> attrList = new ArrayList<>();

        String keyAttrName = null;
        for (DraftHierarchy h : d.hierarchies()) {
            for (DraftLevel l : h.levels()) {
                MondrianDef.Attribute a = new MondrianDef.Attribute();
                a.name = l.name();
                a.keyColumn = calcColumnName(l);
                a.levelType = levelType(l.type());
                a.hasHierarchy = Boolean.FALSE;
                attrList.add(a);
                if (l.type() == DraftLevel.Type.DAYS) {
                    keyAttrName = l.name();
                }
            }
        }
        // If no DAYS level (shouldn't happen for builder output, but guard anyway), fall back
        // to the last level as key.
        if (keyAttrName == null && !attrList.isEmpty()) {
            keyAttrName = attrList.get(attrList.size() - 1).name;
        }
        md.key = keyAttrName;

        attrs.array = attrList.toArray(new MondrianDef.Attribute[0]);
        children.add(attrs);

        children.add(buildHierarchiesHolder(d));

        md.childArray = children.toArray(new MondrianDef.DimensionElement[0]);
        return md;
    }

    private MondrianDef.Hierarchies buildHierarchiesHolder(DraftDimension d) {
        MondrianDef.Hierarchies hiers = new MondrianDef.Hierarchies();
        List<MondrianDef.Hierarchy> hList = new ArrayList<>();
        for (DraftHierarchy h : d.hierarchies()) {
            MondrianDef.Hierarchy mh = new MondrianDef.Hierarchy();
            mh.name = h.name();
            mh.hasAll = Boolean.TRUE;

            List<MondrianDef.HierarchyElement> hChildren = new ArrayList<>();
            for (DraftLevel l : h.levels()) {
                MondrianDef.Level ml = new MondrianDef.Level();
                ml.name = l.name();
                ml.attribute = l.name();
                hChildren.add(ml);
            }
            mh.childArray = hChildren.toArray(new MondrianDef.HierarchyElement[0]);
            hList.add(mh);
        }
        hiers.array = hList.toArray(new MondrianDef.Hierarchy[0]);
        return hiers;
    }

    private String keyAttributeName(DraftDimension d) {
        String pk = primaryKeyOf(d);
        if (pk == null) {
            return null;
        }
        // Build an attribute name from the column. We need a name that is guaranteed not to
        // collide with any level-derived attribute on the same dimension (levels use their
        // level name as the attribute name). Mondrian's Dimension.findAttribute is
        // case-insensitive, so "Id" vs "ID" still clashes — suffix deterministically.
        String candidate = Character.toUpperCase(pk.charAt(0)) + pk.substring(1).toLowerCase();
        String candidateLower = candidate.toLowerCase();
        for (DraftHierarchy h : d.hierarchies()) {
            for (DraftLevel l : h.levels()) {
                if (l.name() != null && l.name().toLowerCase().equals(candidateLower)) {
                    return candidate + "Key";
                }
            }
        }
        return candidate;
    }

    private String levelType(DraftLevel.Type t) {
        if (t == null) {
            return null;
        }
        switch (t) {
            case YEARS:
                return "TimeYears";
            case QUARTERS:
                return "TimeQuarters";
            case MONTHS:
                return "TimeMonths";
            case DAYS:
                return "TimeDays";
            case REGULAR:
            default:
                return null;
        }
    }

    private MondrianDef.Cube buildCube(DraftCube cube) {
        MondrianDef.Cube mc = new MondrianDef.Cube();
        mc.name = cube.name();

        List<MondrianDef.CubeElement> children = new ArrayList<>();

        // Cube-scoped dimensions.
        if (!cube.dimensions().isEmpty()) {
            MondrianDef.Dimensions dims = new MondrianDef.Dimensions();
            List<MondrianDef.Dimension> dlist = new ArrayList<>();
            for (DraftDimension d : cube.dimensions()) {
                dlist.add(buildDimension(d, /* asSharedChildOfSchema= */ false));
            }
            dims.array = dlist.toArray(new MondrianDef.Dimension[0]);
            children.add(dims);
        }

        // Single MeasureGroup bundling all measures for this cube's fact table.
        MondrianDef.MeasureGroups mgs = new MondrianDef.MeasureGroups();
        MondrianDef.MeasureGroup mg = new MondrianDef.MeasureGroup();
        mg.name = cube.name();
        mg.table = cube.sourceFactTable();

        List<MondrianDef.MeasureGroupElement> mgChildren = new ArrayList<>();

        MondrianDef.Measures measures = new MondrianDef.Measures();
        List<MondrianDef.MeasureOrRef> mList = new ArrayList<>();
        for (DraftMeasure m : cube.measures()) {
            MondrianDef.Measure mm = new MondrianDef.Measure();
            mm.name = m.name();
            mm.aggregator = aggregator(m.aggregator());
            // Always emit the column when we have one. For COUNT_STAR we prefer to anchor on the
            // fact PK so Mondrian generates SQL "count(pk)" (≡ count(*) on a non-null PK) rather
            // than evaluating at the tuple level — see MeasureBuilder.
            if (m.column() != null) {
                mm.column = m.column();
            }
            mList.add(mm);
        }
        measures.array = mList.toArray(new MondrianDef.MeasureOrRef[0]);
        mgChildren.add(measures);

        // DimensionLinks — one link per cube dim. ForeignKeyLink for FK-joined dims,
        // FactLink for degenerate TIME dims (which colocate on the fact and therefore have no
        // join condition). Mondrian rejects the schema with "no link for dimension X in measure
        // group Y" if any cube dim is missing a link.
        if (!cube.dimensions().isEmpty()) {
            MondrianDef.DimensionLinks dls = new MondrianDef.DimensionLinks();
            List<MondrianDef.DimensionLink> links = new ArrayList<>();
            for (DraftDimension d : cube.dimensions()) {
                if (d.type() == DraftDimension.Type.TIME) {
                    MondrianDef.FactLink fl = new MondrianDef.FactLink();
                    fl.dimension = d.name();
                    links.add(fl);
                    continue;
                }
                if (d.foreignKey() != null) {
                    MondrianDef.ForeignKeyLink fkl = new MondrianDef.ForeignKeyLink();
                    fkl.dimension = d.name();
                    fkl.foreignKeyColumn = d.foreignKey();
                    links.add(fkl);
                }
            }
            if (!links.isEmpty()) {
                dls.array = links.toArray(new MondrianDef.DimensionLink[0]);
                mgChildren.add(dls);
            }
        }

        mg.childArray = mgChildren.toArray(new MondrianDef.MeasureGroupElement[0]);
        mgs.array = new MondrianDef.MeasureGroup[] {mg};
        children.add(mgs);

        mc.childArray = children.toArray(new MondrianDef.CubeElement[0]);
        return mc;
    }

    private String aggregator(DraftMeasure.Aggregator agg) {
        if (agg == null) {
            return "sum";
        }
        switch (agg) {
            case SUM:
                return "sum";
            case COUNT:
            case COUNT_STAR:
                return "count";
            case AVG:
                return "avg";
            case DISTINCT_COUNT:
                return "distinct-count";
            case MIN:
                return "min";
            case MAX:
                return "max";
            default:
                return "sum";
        }
    }

    // --- degenerate time helpers ------------------------------------------

    /** Synthetic calc-column name used for a TIME level (e.g. {@code order_date_year}). */
    private static String calcColumnName(DraftLevel level) {
        String col = level.column() != null ? level.column() : "time";
        String suffix;
        switch (level.type() == null ? DraftLevel.Type.REGULAR : level.type()) {
            case YEARS:
                suffix = "year";
                break;
            case QUARTERS:
                suffix = "quarter";
                break;
            case MONTHS:
                suffix = "month";
                break;
            case DAYS:
                suffix = "day";
                break;
            default:
                suffix = level.name() == null ? "level" : level.name().toLowerCase();
        }
        return col + "_" + suffix;
    }

    private static void addCalcCols(List<CalcCol> out, DraftDimension d) {
        for (DraftHierarchy h : d.hierarchies()) {
            for (DraftLevel l : h.levels()) {
                String expr = l.expression();
                if (expr == null || expr.isBlank()) {
                    // If no expression present, fall back to the raw column — that reduces the
                    // "calc" to an alias but keeps the schema valid.
                    if (l.column() != null) {
                        expr = l.column();
                    } else {
                        continue;
                    }
                }
                out.add(new CalcCol(calcColumnName(l), expr));
            }
        }
    }

    /** Lightweight pair for the fact-table calc-column collection. */
    private static final class CalcCol {
        final String name;
        final String expression;

        CalcCol(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }
    }
}
