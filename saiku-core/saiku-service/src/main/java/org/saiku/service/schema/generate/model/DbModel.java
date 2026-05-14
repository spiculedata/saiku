package org.saiku.service.schema.generate.model;

import java.util.List;
import java.util.Optional;

/**
 * Root value type for the JDBC-neutral database model consumed by the schema
 * auto-generation pipeline.
 *
 * <p>Holds the set of discovered tables. Downstream stages (classifier,
 * dim/measure/time builders, inferrer) read from this structure and never
 * touch JDBC directly.
 */
public record DbModel(List<DbTable> tables) {

    /**
     * Defensive-copy compact constructor. Rejects {@code null} tables with a
     * clear {@link NullPointerException} and wraps the supplied list with
     * {@link List#copyOf(java.util.Collection)} so the record is genuinely
     * immutable after construction.
     */
    public DbModel {
        tables = List.copyOf(tables);
    }

    /** Factory mirror of the canonical constructor, for call-site readability. */
    public static DbModel of(List<DbTable> tables) {
        return new DbModel(tables);
    }

    /**
     * Look up a table by its (case-sensitive) name. Returns empty when no
     * table matches.
     */
    public Optional<DbTable> tableByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (DbTable t : tables) {
            if (name.equals(t.name())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
