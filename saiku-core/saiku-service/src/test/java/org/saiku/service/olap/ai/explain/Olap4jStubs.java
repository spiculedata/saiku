/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Member;

/**
 * Dynamic-proxy stand-ins for the olap4j types {@link Olap4jCellsetView} reads.
 *
 * <p>Mockito is deliberately not on this module's test classpath (it was removed from the BOM), and
 * the alternative — hand-writing every method of {@code CellSet}, {@code CellSetAxis},
 * {@code Position}, {@code Cell}, {@code metadata.Member} and {@code metadata.Dimension} — would be
 * several hundred lines of stub that tests the stubs. A proxy answers by method name, so the
 * fixture is one dispatcher per interface and the view is exercised through exactly the calls it
 * makes.
 */
final class Olap4jStubs {

    private Olap4jStubs() {}

    /** A dimension member with no parent, i.e. a top-level member of its hierarchy. */
    static Member dimensionMember(String uniqueName, String caption) {
        return member(uniqueName, caption, Dimension.Type.OTHER, null);
    }

    /** A dimension member whose parent is {@code parentUniqueName} — e.g. a quarter under a year. */
    static Member dimensionMember(String parentUniqueName, String uniqueName, String caption) {
        return member(uniqueName, caption, Dimension.Type.OTHER, parentUniqueName);
    }

    /** A member of a measure dimension, i.e. an actual measure. */
    static Member measureMember(String uniqueName, String caption) {
        return member(uniqueName, caption, Dimension.Type.MEASURE, null);
    }

    private static Member member(String uniqueName, String caption, Dimension.Type type, String parentUniqueName) {
        Dimension dimension = proxy(Dimension.class, (m, args) -> "getDimensionType".equals(m.getName()) ? type : null);
        Member parent = parentUniqueName == null
                ? null
                : member(parentUniqueName, parentUniqueName, Dimension.Type.OTHER, null);
        return proxy(Member.class, (m, args) -> switch (m.getName()) {
            case "getUniqueName" -> uniqueName;
            case "getCaption", "getName" -> caption;
            case "getDimension" -> dimension;
            case "getParentMember" -> parent;
            default -> null;
        });
    }

    static Position position(Member... members) {
        return proxy(Position.class, (m, args) -> "getMembers".equals(m.getName()) ? List.of(members) : null);
    }

    /**
     * A cellset whose {@code (column, row)} cells hold {@code values[column][row]}; a
     * {@code NaN} entry is an empty cell, as in olap4j. Column tuples come first, then row tuples.
     */
    static CellSet cellSet(List<List<Member>> columns, List<List<Member>> rows, double[][] values) {
        Map<Position, Integer> ordinals = new IdentityHashMap<>();
        List<Position> columnPositions = positions(columns, ordinals);
        List<Position> rowPositions = positions(rows, ordinals);
        List<CellSetAxis> axes = new ArrayList<>();
        axes.add(axis(columnPositions));
        if (!rowPositions.isEmpty()) {
            axes.add(axis(rowPositions));
        }

        return proxy(CellSet.class, (m, args) -> {
            if ("getAxes".equals(m.getName())) {
                return axes;
            }
            if ("getCell".equals(m.getName())) {
                return cell(args, ordinals, values);
            }
            return null;
        });
    }

    private static List<Position> positions(List<List<Member>> tuples, Map<Position, Integer> ordinals) {
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < tuples.size(); i++) {
            Position position = position(tuples.get(i).toArray(new Member[0]));
            ordinals.put(position, i);
            out.add(position);
        }
        return out;
    }

    private static CellSetAxis axis(List<Position> positions) {
        return proxy(CellSetAxis.class, (m, args) -> "getPositions".equals(m.getName()) ? positions : null);
    }

    private static Cell cell(Object[] args, Map<Position, Integer> ordinals, double[][] values) {
        Object first = args[0];
        if (!(first instanceof Position[] positions) || positions.length == 0) {
            throw new IllegalArgumentException("stub expects getCell(Position...), got " + first);
        }
        Integer column = ordinals.get(positions[0]);
        // No row position means a one-axis (WHERE-only) result, which olap4j addresses with the
        // column position alone and renders as a single row.
        Integer row = positions.length > 1 ? ordinals.get(positions[1]) : 0;
        if (column == null || row == null) {
            throw new IllegalArgumentException("stub cellset does not know that position");
        }
        double value = values[column][row];
        return proxy(Cell.class, (m, cellArgs) -> switch (m.getName()) {
            case "getValue" -> value;
            case "getFormattedValue" -> Double.isNaN(value) ? null : String.format(Locale.ROOT, "%,.2f", value);
            case "isEmpty", "isNull" -> Double.isNaN(value);
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, Dispatcher dispatcher) {
        Object stub = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == (args == null ? null : args[0]);
                case "toString":
                    return type.getSimpleName() + "-stub";
                default:
                    return dispatcher.dispatch(method, args);
            }
        });
        return type.cast(stub);
    }

    @FunctionalInterface
    private interface Dispatcher {
        Object dispatch(Method method, Object[] args);
    }
}
