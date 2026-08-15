/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.olap4j.OlapConnection;
import org.olap4j.metadata.Cube;

/**
 * Boots a REAL Mondrian 4 cube in-process, so conversions that need live olap4j metadata can be
 * property-tested against the actual engine rather than a mock.
 *
 * <p>Uses the shipped Bank demo — {@code seed/bank.sql} (135 lines of DDL + rows) and
 * {@code seed/Bank.xml} (Mondrian 4.0, six cubes) — both git-tracked, so this works on a clean CI
 * checkout. The alternative fixture, {@code mondrian-data-foodmart-hsql}, is a 73 MB download whose
 * {@code FoodMart.xml} is a Mondrian THREE schema and so will not load in the Spicule fork without
 * conversion.
 *
 * <p>The database is H2 in-memory with {@code DB_CLOSE_DELAY=-1}, and the connection is opened once
 * and cached: schema load is the expensive part, and every property here is read-only.
 */
final class BankCubeHarness {

    /** Resolved from the module root — saiku-proptest sits one level below the reactor root. */
    private static final Path SEED = Path.of("..", "saiku-launcher", "src", "main", "resources", "seed");

    private static final String JDBC_URL = "jdbc:h2:mem:saiku-proptest-bank;DB_CLOSE_DELAY=-1";

    private static volatile OlapConnection connection;
    private static volatile List<Cube> cubes;

    private BankCubeHarness() {}

    /** True when the seed files are present; lets a property skip rather than fail in odd layouts. */
    static boolean isAvailable() {
        return Files.isReadable(SEED.resolve("bank.sql")) && Files.isReadable(SEED.resolve("Bank.xml"));
    }

    /** The live olap4j connection, for helpers that need the connection rather than a cube. */
    static OlapConnection connection() throws Exception {
        return load();
    }

    /** The live cubes from the Bank schema, loaded once. */
    static synchronized List<Cube> cubes() throws Exception {
        if (cubes == null) {
            cubes = List.copyOf(load().getOlapSchema().getCubes());
        }
        return cubes;
    }

    /** Look a cube up by name; null when the schema doesn't carry it. */
    static Cube cube(String name) throws Exception {
        for (Cube c : cubes()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** Cubes carrying at least one dimension AND one measure — the shape a query needs. */
    static List<Cube> queryableCubes() throws Exception {
        List<Cube> out = new ArrayList<>();
        for (Cube c : cubes()) {
            if (!c.getMeasures().isEmpty() && c.getDimensions().size() > 1) {
                out.add(c);
            }
        }
        return out;
    }

    private static synchronized OlapConnection load() throws Exception {
        if (connection != null) {
            return connection;
        }
        Class.forName("org.h2.Driver");
        try (Connection jdbc = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = jdbc.createStatement()) {
            st.execute(Files.readString(SEED.resolve("bank.sql"), StandardCharsets.UTF_8));
        }
        Class.forName("mondrian.olap4j.MondrianOlap4jDriver");
        String mondrianUrl = "jdbc:mondrian:Jdbc=" + JDBC_URL + ";Catalog=file:"
                + SEED.resolve("Bank.xml").toAbsolutePath() + ";JdbcDrivers=org.h2.Driver;JdbcUser=sa;JdbcPassword=";
        connection = DriverManager.getConnection(mondrianUrl).unwrap(OlapConnection.class);
        return connection;
    }
}
