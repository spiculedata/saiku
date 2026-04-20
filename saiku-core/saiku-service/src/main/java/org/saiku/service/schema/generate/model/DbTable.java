package org.saiku.service.schema.generate.model;

import java.util.List;

/**
 * A table (or view) in a relational source, captured in JDBC-neutral form.
 *
 * <p>{@code schema} is the JDBC schema name and may be {@code null} or empty
 * for databases that do not expose schemas. {@code rowCountEstimate} may be
 * {@code null} when unknown.
 *
 * <p>Pure value type.
 */
public record DbTable(
        String schema, String name, List<DbColumn> columns, List<DbForeignKey> foreignKeys, Long rowCountEstimate) {

    /**
     * Defensive-copy compact constructor. Rejects {@code null} column and
     * foreign-key lists with a clear {@link NullPointerException} and wraps
     * them with {@link List#copyOf(java.util.Collection)} so the record is
     * genuinely immutable after construction.
     */
    public DbTable {
        columns = List.copyOf(columns);
        foreignKeys = List.copyOf(foreignKeys);
    }
}
