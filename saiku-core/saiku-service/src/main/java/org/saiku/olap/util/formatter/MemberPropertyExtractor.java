/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.olap4j.OlapException;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.NamedList;
import org.olap4j.metadata.Property;

/**
 * Pulls olap4j {@link Property} values off a {@link Member} and surfaces
 * them as a flat {@code Map<String,String>}, the member-level mirror of
 * {@link CellPropertyExtractor}. Powers saiku#827 — the member-property
 * follow-up to saiku#773 / PR #821, which only covered {@link org.olap4j.Cell}
 * properties.
 *
 * <p>{@link Member#getProperties()} returns every property applicable to the
 * member: the standard XMLA/olap4j member properties (name, caption,
 * description, ordinal, ...) *and* any custom properties a schema author
 * defined on the member's level (currency code, region grouping, an
 * "is-active" flag, ...). We skip the standard properties that are already
 * surfaced through other {@code MemberCell} fields (unique name, hierarchy,
 * level, ...) or carry no rendering/filtering value on their own, so the
 * resulting map stays focused on metadata a client can't already get off
 * the DTO — schema-defined custom properties, plus the handful of standard
 * ones worth keeping (description, member key, caption, ordinal, children
 * cardinality, parent unique name).
 *
 * <p>Null/empty values are skipped and property-read failures are treated as
 * absent, the same convention {@link CellPropertyExtractor} uses — Mondrian
 * and XMLA drivers can throw when a property isn't materialised for a given
 * member.
 */
public final class MemberPropertyExtractor {

    private MemberPropertyExtractor() {}

    /**
     * Standard olap4j/XMLA member properties already surfaced elsewhere on
     * {@code MemberCell} (unique name, hierarchy/level identifiers, parent
     * bookkeeping, ...) or with no standalone rendering value. Kept as plain
     * name strings — matched against {@link Property#getName()} — rather
     * than {@link Property.StandardMemberProperty} enum constants, since the
     * exact constant set varies across olap4j providers and this filter only
     * needs to trim well-known noise, not be exhaustive.
     */
    private static final Set<String> SKIP = Set.of(
            "CATALOG_NAME",
            "SCHEMA_NAME",
            "CUBE_NAME",
            "DIMENSION_UNIQUE_NAME",
            "HIERARCHY_UNIQUE_NAME",
            "LEVEL_UNIQUE_NAME",
            "LEVEL_NUMBER",
            "MEMBER_NAME",
            "MEMBER_UNIQUE_NAME",
            "MEMBER_TYPE",
            "MEMBER_GUID",
            "PARENT_LEVEL",
            "PARENT_COUNT",
            "VALUE",
            "$visible");

    /**
     * Read every non-skipped property olap4j reports for this member.
     * Returns an insertion-ordered map so JSON serialisation is
     * deterministic across runs.
     */
    public static Map<String, String> extract(Member member) {
        Map<String, String> out = new LinkedHashMap<>();
        if (member == null) return out;

        NamedList<Property> properties;
        try {
            properties = member.getProperties();
        } catch (RuntimeException ignored) {
            return out;
        }
        if (properties == null) return out;

        for (Property property : properties) {
            putIfNonNull(out, member, property);
        }

        return out;
    }

    private static void putIfNonNull(Map<String, String> out, Member member, Property property) {
        if (property == null) return;
        String name = property.getName();
        if (name == null || name.isEmpty() || SKIP.contains(name)) return;

        try {
            Object v = member.getPropertyValue(property);
            if (v == null) return;
            String s = v.toString();
            if (s.isEmpty()) return;
            out.put(name, s);
        } catch (OlapException | RuntimeException ignored) {
            // Some properties throw if the provider hasn't materialised
            // them for this member — treat as absent, same as
            // CellPropertyExtractor.
        }
    }
}
