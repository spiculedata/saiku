/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses a Mondrian schema XML into a {@link ModelSnapshot} (saiku#1434).
 *
 * <p>Handles both schema shapes Saiku ships: classic-3 (measures hang directly off the cube) and
 * Mondrian 4 ({@code <MeasureGroup><Measures>}), plus virtual cubes, shared dimensions reached
 * through {@code <DimensionUsage>} aliases, hierarchies/levels, and calculated members.
 *
 * <p>Two details carry most of the weight for correctness:
 *
 * <ul>
 *   <li>A {@code <DimensionUsage name="Product" source="Product">} makes {@code Product} a real
 *       dimension <em>in that cube</em>, and MDX addresses it as {@code [Product].[Product
 *       Family]}. Its levels are therefore re-projected from the shared dimension under the
 *       alias name. A validator that only understood cube-local {@code <Dimension>} elements
 *       would report every such level as broken — a false positive on a correct dashboard.
 *   <li>Element signatures exclude the {@code name} attribute, which is what makes
 *       "same element, different name" recognisable as a rename rather than an
 *       add-plus-remove.
 * </ul>
 *
 * <p>XML parsing is hardened: DOCTYPE declarations, external entities and external DTD/schema
 * access are all refused. Model payloads arrive from REST bodies and from git refs, so an
 * XXE-capable parser here would be a file-read primitive reachable by any admin.
 */
public final class MondrianXmlModelParser implements ModelParser {

    @Override
    public ModelFormat format() {
        return ModelFormat.MONDRIAN_XML;
    }

    @Override
    public ModelSnapshot parse(String content, String sourceName) {
        if (content == null || content.isBlank()) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " is empty — expected a Mondrian XML schema");
        }
        Document doc = document(content, sourceName);
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, describe(sourceName) + " has no document element");
        }

        // Shared dimensions live at <Schema> scope, one level up from a cube, so the alias
        // resolution below needs a schema-scoped index built once per parse.
        Map<String, List<LevelInfo>> sharedLevels = new LinkedHashMap<>();
        for (Element dimension : dimensionElements(root)) {
            String name = attr(dimension, "name");
            if (name != null) {
                sharedLevels.put(normalise(name), levelsOf(dimension));
            }
        }

        List<ModelElement> out = new ArrayList<>();
        for (Element cube : childrenNamed(root, "Cube")) {
            readCube(cube, sharedLevels, out);
        }
        // A virtual cube carries no measures of its own — they come from the cubes it references —
        // but it IS a cube as far as a saved query is concerned, so it is tracked like one.
        for (Element virtual : childrenNamed(root, "VirtualCube")) {
            readCube(virtual, sharedLevels, out);
        }
        return new ModelSnapshot(ModelFormat.MONDRIAN_XML, attr(root, "name"), out);
    }

    private void readCube(Element cube, Map<String, List<LevelInfo>> sharedLevels, List<ModelElement> out) {
        String cubeName = attr(cube, "name");
        if (cubeName == null) {
            return;
        }
        out.add(new ModelElement(ModelElementKind.CUBE, cubeName, null, cubeName, signature(cube)));

        // Measures: classic-3 direct children and Mondrian 4 MeasureGroups alike. The Mondrian
        // schema vocabulary has moved the container element name around across versions
        // (<MeasureGroup> in older M4 drafts, <MeasureGroups> in what Saiku ships), so both are
        // accepted rather than a schema version we have never seen being read as "no measures".
        for (Element measure : childrenNamed(cube, "Measure")) {
            addMeasure(cubeName, measure, out);
        }
        for (String groupElement : List.of("MeasureGroup", "MeasureGroups")) {
            for (Element group : childrenNamed(cube, groupElement)) {
                // A <MeasureGroups> container holds <MeasureGroup>s; a bare <MeasureGroup> does
                // not. Accept both nestings.
                List<Element> groups = new ArrayList<>();
                groups.add(group);
                groups.addAll(childrenNamed(group, "MeasureGroup"));
                for (Element actual : groups) {
                    for (Element holder : childrenNamed(actual, "Measures")) {
                        for (Element measure : childrenNamed(holder, "Measure")) {
                            addMeasure(cubeName, measure, out);
                        }
                    }
                }
            }
        }
        // Cube-scope calculated members are addressed as [Measures].[x] in MDX. They sit either
        // directly under the cube or inside a <CalculatedMembers> container.
        for (Element member : descendantsNamed(cube, "CalculatedMember")) {
            addMeasure(cubeName, member, out);
        }

        // Cube-local dimensions, their attributes/levels, and any calculated levels. A dimension
        // element may carry a <Dimensions> container, may reference a schema-scoped dimension
        // with source= and no name, and may declare its levels either as <Level name=...> or as
        // <Level attribute=...> pointing at an <Attribute> — all three appear in schemas Saiku
        // ships, and getting any of them wrong turns every dashboard using that dimension into a
        // false positive.
        Map<String, List<LevelInfo>> localLevels = new LinkedHashMap<>();
        for (Element dimension : dimensionElements(cube)) {
            String alias = attr(dimension, "name");
            String source = attr(dimension, "source");
            if (alias == null) {
                alias = source; // <Dimension source='Store'/> — the alias IS the source name
            }
            if (alias == null) {
                continue;
            }
            List<LevelInfo> levels = new ArrayList<>();
            if (localLevels.containsKey(normalise(alias)) || source != null) {
                // A source reference inherits the shared dimension's levels under the alias.
                levels.addAll(sharedLevels.getOrDefault(normalise(source), List.of()));
            } else {
                levels.addAll(levelsOf(dimension));
            }
            if (localLevels.putIfAbsent(normalise(alias), levels) == null) {
                out.add(new ModelElement(
                        ModelElementKind.DIMENSION,
                        cubeName,
                        null,
                        alias,
                        signature(dimension),
                        attr(dimension, "caption")));
                for (LevelInfo level : levels) {
                    out.add(new ModelElement(
                            ModelElementKind.LEVEL, cubeName, alias, level.name(), level.signature(), level.caption()));
                }
            }
            for (Element member : calculatedMembers(dimension)) {
                String name = attr(member, "name");
                if (name != null) {
                    out.add(new ModelElement(ModelElementKind.LEVEL, cubeName, alias, name, signature(member)));
                }
            }
        }

        // <DimensionUsage name="Product" source="Product"/> is the explicit-alias spelling of the
        // same idea. The alias is a dimension IN THIS CUBE and MDX addresses its levels through
        // the alias, so its levels are projected under the alias too.
        for (Element usage : childrenNamed(cube, "DimensionUsage")) {
            String alias = attr(usage, "name");
            String source = attr(usage, "source");
            if (alias == null || source == null || localLevels.containsKey(normalise(alias))) {
                continue;
            }
            List<LevelInfo> levels = localLevels.get(normalise(source));
            if (levels == null) {
                levels = sharedLevels.getOrDefault(normalise(source), List.of());
            }
            out.add(new ModelElement(ModelElementKind.DIMENSION, cubeName, null, alias, "source=" + normalise(source)));
            for (LevelInfo level : levels) {
                out.add(new ModelElement(ModelElementKind.LEVEL, cubeName, alias, level.name(), level.signature()));
            }
        }
    }

    /** A resolved level: its addressable name, the fingerprint used for rename detection, and its caption. */
    private record LevelInfo(String name, String signature, String caption) {
        LevelInfo(String name, String signature) {
            this(name, signature, null);
        }
    }

    /**
     * The dimension elements of a cube: direct {@code <Dimension>} children (classic-3) and
     * those wrapped in a {@code <Dimensions>} container (Mondrian 4).
     */
    private static List<Element> dimensionElements(Element cube) {
        List<Element> out = new ArrayList<>(childrenNamed(cube, "Dimension"));
        for (Element container : childrenNamed(cube, "Dimensions")) {
            out.addAll(childrenNamed(container, "Dimension"));
        }
        return out;
    }

    /**
     * Resolve a dimension's addressable levels.
     *
     * <p>Three sources, unioned:
     *
     * <ul>
     *   <li>{@code <Level name="X">} — classic-3, and Mondrian 4 when the level is renamed away
     *       from its attribute.
     *   <li>{@code <Level attribute="X">} — Mondrian 4, where the level is addressed by the name
     *       of the {@code <Attribute>} it points at, not by the attribute key.
     *   <li>the dimension's own {@code <Attribute>} elements — in Mondrian 4 an attribute with
     *       {@code hasHierarchy="false"} IS a level a query can select, and a validator that
     *       missed those would flag valid queries. Extra entries can only ever suppress a false
     *       positive, never invent one.
     * </ul>
     */
    private static List<LevelInfo> levelsOf(Element dimension) {
        Map<String, LevelInfo> levels = new LinkedHashMap<>();
        // <Attribute> and <Level> may sit directly under the dimension, or inside the
        // <Attributes> / <Hierarchies> / <LevelGroups> containers Mondrian 4 uses. Descendant
        // search rather than a fixed child list, so a schema that wraps them differently still
        // reads completely — an under-read dimension is a false-positive machine.
        for (Element attribute : descendantsNamed(dimension, "Attribute")) {
            String name = attr(attribute, "name");
            if (name != null) {
                levels.putIfAbsent(name, new LevelInfo(name, signature(attribute), attr(attribute, "caption")));
            }
        }
        for (Element level : descendantsNamed(dimension, "Level")) {
            String name = levelName(level);
            if (name != null) {
                levels.putIfAbsent(name, new LevelInfo(name, signature(level), attr(level, "caption")));
            }
        }
        return new ArrayList<>(levels.values());
    }

    /**
     * The addressable name of a {@code <Level>}: its own {@code name} when present, else the
     * attribute it points at. In the Mondrian 4 shape Saiku ships, {@code <Level attribute='Store
     * City'/>} resolves to the <em>name</em> of the {@code <Attribute>} that declares it — and
     * {@code <Attribute name='Store City'>} is how a query addresses it, so the attribute-key
     * spelling is correct for every schema in the repository, with the attribute's own entry
     * covering the rest.
     */
    private static String levelName(Element level) {
        String name = attr(level, "name");
        return name != null ? name : attr(level, "attribute");
    }

    /**
     * Add a cube-scope member — a {@code <Measure>} or a cube-scope {@code <CalculatedMember>},
     * which address identically as {@code [Measures].[name]}.
     *
     * <p>Calculated members are the reason the caption is carried: FoodMart4 captions
     * {@code Store Sales Growth} as {@code "MoM Growth"}, and the shipped demo app binds that
     * caption. Resolving captions is the difference between a true finding and a false positive
     * on a tile that demonstrably works.
     */
    private void addMeasure(String cubeName, Element measure, List<ModelElement> out) {
        String name = attr(measure, "name");
        if (name != null) {
            out.add(new ModelElement(
                    ModelElementKind.MEASURE, cubeName, null, name, signature(measure), attr(measure, "caption")));
        }
    }

    /**
     * Every {@code <CalculatedMember>} under a dimension, at any depth. They appear directly
     * under the dimension, under a {@code <Hierarchy>} and under a {@code <Rollup>} depending on
     * which part of the schema was hand-edited, and all three address as
     * {@code [Dimension].[Name]}.
     */
    private static List<Element> calculatedMembers(Element dimension) {
        return descendantsNamed(dimension, "CalculatedMember");
    }

    private static void collectDescendants(Element parent, String name, List<Element> out) {
        for (Element child : childrenNamed(parent, name)) {
            out.add(child);
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collectDescendants((Element) node, name, out);
            }
        }
    }

    /** Every descendant element with the given tag name, at any depth. */
    private static List<Element> descendantsNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        collectDescendants(parent, name, out);
        return out;
    }

    /* ---------------- DOM helpers ---------------- */

    private Document document(String content, String sourceName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException e) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED,
                    describe(sourceName) + " is not well-formed XML (" + e.getMessage() + ")",
                    e);
        }
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static String attr(Element element, String name) {
        if (!element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A stable fingerprint of an element's attributes excluding {@code name}: sorted so source
     * attribute order does not matter, lower-cased because {@code column="UNIT_SALES"} and
     * {@code column="unit_sales"} name the same physical column.
     */
    private static String signature(Element element) {
        Map<String, String> attrs = new LinkedHashMap<>();
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node node = map.item(i);
            String key = node.getNodeName().toLowerCase(Locale.ROOT);
            if ("name".equals(key)) {
                continue;
            }
            attrs.put(
                    key,
                    node.getNodeValue() == null
                            ? ""
                            : node.getNodeValue().trim().toLowerCase(Locale.ROOT));
        }
        return attrs.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String describe(String sourceName) {
        return sourceName == null || sourceName.isBlank() ? "the schema payload" : "'" + sourceName + "'";
    }
}
