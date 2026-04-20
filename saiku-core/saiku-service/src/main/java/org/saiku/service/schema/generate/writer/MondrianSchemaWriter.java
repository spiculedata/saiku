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
 *       and a {@code Link} per cube-scoped dimension FK.
 *   <li>Shared dimensions as top-level {@code Dimension} elements ({@code type="TIME"} for {@link
 *       DraftDimension.Type#TIME}), each with an {@code Attributes} holder (one {@code Attribute}
 *       per level) and a {@code Hierarchies} holder (one {@code Hierarchy} per draft hierarchy
 *       with {@code Level} elements referencing the attributes).
 *   <li>Each cube with its private dimensions in a {@code Dimensions} holder, a single {@code
 *       MeasureGroup} whose {@code Measures} holder contains one {@code Measure} per draft
 *       measure, and {@code DimensionLinks} containing a {@code ForeignKeyLink} per dimension.
 * </ul>
 *
 * <p>Known simplifications (tracked for future tasks, not blockers for A9):
 *
 * <ul>
 *   <li>{@link DraftLevel} has no {@code nameColumn} field, so attributes only carry {@code
 *       keyColumn}.
 *   <li>Snowflake joins ({@link org.saiku.service.schema.generate.draft.DraftJoin}) are not yet
 *       emitted — the one-hop lookup table is not added to the PhysicalSchema. Dimensions with a
 *       join will still emit their primary source table but not the join table.
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

        // dimensions from cubes + shared
        List<DraftDimension> allDims = new ArrayList<>();
        allDims.addAll(draft.sharedDimensions());
        for (DraftCube cube : draft.cubes()) {
            if (cube.sourceFactTable() != null) {
                factTables.add(cube.sourceFactTable());
            }
            allDims.addAll(cube.dimensions());
        }

        for (DraftDimension d : allDims) {
            if (d.sourceTable() == null) {
                continue;
            }
            String pk = primaryKeyOf(d);
            if (pk != null && !dimTables.containsKey(d.sourceTable())) {
                dimTables.put(d.sourceTable(), pk);
            } else {
                dimTables.putIfAbsent(d.sourceTable(), pk);
            }
        }

        List<MondrianDef.PhysicalSchemaElement> elements = new ArrayList<>();

        for (String fact : factTables) {
            elements.add(simpleTable(fact, null));
        }
        for (Map.Entry<String, String> e : dimTables.entrySet()) {
            elements.add(simpleTable(e.getKey(), e.getValue()));
        }

        // Fact → dim Links (one per cube-scoped dimension with a foreign key).
        for (DraftCube cube : draft.cubes()) {
            if (cube.sourceFactTable() == null) {
                continue;
            }
            for (DraftDimension d : cube.dimensions()) {
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

    private MondrianDef.Table simpleTable(String name, String keyColumn) {
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

    private String primaryKeyOf(DraftDimension d) {
        if (d.hierarchies().isEmpty()) {
            return null;
        }
        return d.hierarchies().get(0).primaryKey();
    }

    /** Build a Mondrian Dimension from a draft dim. */
    private MondrianDef.Dimension buildDimension(DraftDimension d, boolean asSharedChildOfSchema) {
        MondrianDef.Dimension md = new MondrianDef.Dimension();
        md.name = d.name();
        if (d.type() == DraftDimension.Type.TIME) {
            md.type = "TIME";
        }
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

        // Attributes holder: one per level across all hierarchies, plus a dedicated key attribute.
        MondrianDef.Attributes attrs = new MondrianDef.Attributes();
        List<MondrianDef.Attribute> attrList = new ArrayList<>();

        // Key attribute (keyed on primary key column, no hierarchy of its own).
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
                a.levelType = levelType(l.type());
                a.hasHierarchy = Boolean.FALSE;
                attrList.add(a);
            }
        }
        attrs.array = attrList.toArray(new MondrianDef.Attribute[0]);
        children.add(attrs);

        // Hierarchies holder.
        if (!d.hierarchies().isEmpty()) {
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
            children.add(hiers);
        }

        md.childArray = children.toArray(new MondrianDef.DimensionElement[0]);
        return md;
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

        // DimensionLinks — one ForeignKeyLink per cube dim with a FK.
        if (!cube.dimensions().isEmpty()) {
            MondrianDef.DimensionLinks dls = new MondrianDef.DimensionLinks();
            List<MondrianDef.DimensionLink> links = new ArrayList<>();
            for (DraftDimension d : cube.dimensions()) {
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
}
