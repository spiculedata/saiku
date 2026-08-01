/**
 * Mondrian 4 XML → Mondrian 4 YAML (saiku-cloud#1080).
 *
 * The dashboard's canvas Code tab renders the schema as XML
 * (`workbenchToMondrianPreview`); this converts that same XML into the M4 YAML
 * the engine accepts, so the Code tab can offer an XML/YAML toggle without a
 * second, drift-prone emitter. It mirrors the engine's own `M4XmlToYaml` and
 * the format documented in `docs-site/.../mondrian/yaml-schemas.mdx` (canonical
 * fixture: mondrian-saiku `demo/FoodMart.yaml`).
 *
 * PURE + isomorphic (browser + Node/vitest). Handles exactly the M4 constructs
 * the canvas emits — PhysicalSchema tables/keys, shared Dimensions (attributes +
 * hierarchies), Cubes (dimension usages, measure groups, measures, dimension
 * links). Never throws: returns a short `# comment` string on unparseable input.
 */
import { stringify } from "yaml";
import {
  parseXml,
  childNamed,
  childrenNamed,
  type XmlNode,
} from "./xml-parser";

type Yaml = Record<string, unknown>;

/** M4 XML string → M4 YAML string. */
export function mondrianXmlToYaml(xml: string): string {
  // The preview interleaves `<!-- ... -->` placeholder comments; strip them so
  // the parser sees clean markup (YAML has its own `#` comments if needed).
  const cleaned = xml.replace(/<!--[\s\S]*?-->/g, "");
  const parsed = parseXml(cleaned);
  if (!parsed.ok || parsed.root.name !== "Schema") {
    return "# Could not render YAML — the schema XML is incomplete.\n";
  }
  const schema = parsed.root;

  const doc: Yaml = {};
  doc.schema = {
    name: schema.attributes.name ?? "Untitled",
    metamodel_version: schema.attributes.metamodelVersion ?? "4.0",
  };

  const physical = childNamed(schema, "PhysicalSchema");
  if (physical) {
    const tables = childrenNamed(physical, "Table").map(tableToYaml);
    const links = childrenNamed(physical, "Link").map((l) => ({
      source: l.attributes.source,
      target: l.attributes.target,
      ...keyOrKeyColumn(
        l,
        "foreignKeyColumn",
        "foreign_key_column",
        "foreign_key",
      ),
    }));
    const ps: Yaml = {};
    if (tables.length) ps.tables = tables;
    if (links.length) ps.links = links;
    if (Object.keys(ps).length) doc.physical_schema = ps;
  }

  // Shared dimensions are the schema-level <Dimension> elements.
  const sharedDims = childrenNamed(schema, "Dimension");
  if (sharedDims.length) {
    const map: Yaml = {};
    for (const d of sharedDims) {
      map[d.attributes.name ?? "Dimension"] = dimensionBodyToYaml(d);
    }
    doc.shared_dimensions = map;
  }

  const cubes = childrenNamed(schema, "Cube");
  if (cubes.length) {
    const map: Yaml = {};
    for (const c of cubes) {
      map[c.attributes.name ?? "Cube"] = cubeToYaml(c);
    }
    doc.cubes = map;
  }

  return stringify(doc, {
    lineWidth: 0,
    defaultStringType: "QUOTE_DOUBLE",
    defaultKeyType: "PLAIN",
  });
}

function tableToYaml(t: XmlNode): Yaml {
  const out: Yaml = { name: t.attributes.name };
  if (t.attributes.schema) out.schema = t.attributes.schema;
  if (t.attributes.alias) out.alias = t.attributes.alias;
  Object.assign(out, keyOrKeyColumn(t, "keyColumn", "key_column", "key"));
  return out;
}

/**
 * Resolve an element's single-column key (`keyColumn` attr) or composite key
 * (`<Key><Column name=.../></Key>` children) into YAML `key_column` / `key`.
 */
function keyOrKeyColumn(
  node: XmlNode,
  attrName: string,
  singleKey: string,
  listKey: string,
): Yaml {
  const single = node.attributes[attrName];
  if (single) return { [singleKey]: single };
  const key = childNamed(node, "Key");
  if (key) {
    const cols = childrenNamed(key, "Column")
      .map((c) => c.attributes.name)
      .filter((n): n is string => !!n);
    if (cols.length) return { [listKey]: cols };
  }
  return {};
}

function dimensionBodyToYaml(d: XmlNode): Yaml {
  const out: Yaml = {};
  if (d.attributes.table) out.table = d.attributes.table;
  if (d.attributes.key) out.key = d.attributes.key;
  if (d.attributes.type)
    out.type = d.attributes.type.replace(/Dimension$/, "").toUpperCase();

  const attrsNode = childNamed(d, "Attributes");
  if (attrsNode) {
    const attrs = childrenNamed(attrsNode, "Attribute").map(attributeToYaml);
    if (attrs.length) out.attributes = attrs;
  }
  const hierNode = childNamed(d, "Hierarchies");
  if (hierNode) {
    const hiers = childrenNamed(hierNode, "Hierarchy").map(hierarchyToYaml);
    if (hiers.length) out.hierarchies = hiers;
  }
  return out;
}

function attributeToYaml(a: XmlNode): Yaml {
  const out: Yaml = { name: a.attributes.name };
  Object.assign(out, keyOrKeyColumn(a, "keyColumn", "key_column", "key"));
  if (a.attributes.nameColumn) out.name_column = a.attributes.nameColumn;
  if (a.attributes.levelType) out.level_type = a.attributes.levelType;
  if (a.attributes.hasHierarchy === "false") out.has_hierarchy = false;
  if (a.attributes.hierarchyAllMemberName) {
    out.hierarchy_all_member_name = a.attributes.hierarchyAllMemberName;
  }
  return out;
}

function hierarchyToYaml(h: XmlNode): Yaml {
  const out: Yaml = { name: h.attributes.name };
  if (h.attributes.allMemberName)
    out.all_member_name = h.attributes.allMemberName;
  if (h.attributes.defaultMember)
    out.default_member = h.attributes.defaultMember;
  if (h.attributes.hasAll) out.has_all = h.attributes.hasAll === "true";
  // Each <Level attribute="X"/> becomes a bare string (level name == attribute).
  const levels = childrenNamed(h, "Level").map(
    (l) => l.attributes.attribute ?? l.attributes.name,
  );
  out.levels = levels;
  return out;
}

function cubeToYaml(c: XmlNode): Yaml {
  const out: Yaml = {};
  const dimsNode = childNamed(c, "Dimensions");
  if (dimsNode) {
    const dims = childrenNamed(dimsNode, "Dimension").map((d) =>
      d.attributes.source
        ? { source: d.attributes.source }
        : dimensionBodyToYaml(d),
    );
    if (dims.length) out.dimensions = dims;
  }
  const mgsNode = childNamed(c, "MeasureGroups");
  if (mgsNode) {
    const mgs = childrenNamed(mgsNode, "MeasureGroup").map(measureGroupToYaml);
    if (mgs.length) out.measure_groups = mgs;
  }
  return out;
}

function measureGroupToYaml(mg: XmlNode): Yaml {
  const out: Yaml = {};
  if (mg.attributes.name) out.name = mg.attributes.name;
  out.table = mg.attributes.table;

  const measuresNode = childNamed(mg, "Measures");
  if (measuresNode) {
    const measures = childrenNamed(measuresNode, "Measure").map((m) => {
      const mo: Yaml = { name: m.attributes.name };
      if (m.attributes.column) mo.column = m.attributes.column;
      mo.aggregator = m.attributes.aggregator ?? "sum";
      if (m.attributes.formatString)
        mo.format_string = m.attributes.formatString;
      return mo;
    });
    if (measures.length) out.measures = measures;
  }

  const linksNode = childNamed(mg, "DimensionLinks");
  if (linksNode) {
    const links = linksNode.children
      .map(dimensionLinkToYaml)
      .filter((l): l is Yaml => l !== null);
    if (links.length) out.dimension_links = links;
  }
  return out;
}

function dimensionLinkToYaml(link: XmlNode): Yaml | null {
  const dimension = link.attributes.dimension;
  if (!dimension) return null;
  switch (link.name) {
    case "ForeignKeyLink":
      return {
        type: "foreign_key",
        dimension,
        ...keyOrKeyColumn(
          link,
          "foreignKeyColumn",
          "foreign_key_column",
          "foreign_key",
        ),
      };
    case "FactLink":
      return { type: "fact", dimension };
    case "ReferenceLink": {
      const out: Yaml = { type: "reference", dimension };
      if (link.attributes.viaDimension)
        out.via_dimension = link.attributes.viaDimension;
      if (link.attributes.viaAttribute)
        out.via_attribute = link.attributes.viaAttribute;
      return out;
    }
    default:
      return null;
  }
}
