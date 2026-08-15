/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;

/**
 * Small null-safe coercions from the opaque, Jackson-deserialised job payload (a {@code Map<String,
 * Object>} of {@code String}/{@code Number}/{@code Boolean}/{@code List}/{@code Map}) into the typed
 * fields a {@link ThresholdAlertSpec} needs (saiku#1098). Kept separate so the spec + channel-config
 * parsers share exactly one interpretation of the wire shape.
 *
 * <p>Public so the sibling {@code DASHBOARD_DIGEST} digest package (saiku#943) reuses exactly this one
 * interpretation of the payload wire shape rather than re-deriving it.
 */
public final class PayloadParsing {

    private PayloadParsing() {}

    public static String readString(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Accepts a JSON number or a numeric string; returns null when absent, throws on a non-number. */
    public static Double readDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number but got '" + v + "'");
        }
    }

    /**
     * A cube ref, accepting either a {@code "conn/catalog/schema/cube"} string or a
     * {@code {connectionName, catalog, schema, cubeName}} map. Null / malformed returns null.
     */
    public static AiCubeRef readCube(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof AiCubeRef ref) {
            return ref;
        }
        if (v instanceof String s) {
            String[] parts = s.split("/", -1);
            if (parts.length != 4) {
                return null;
            }
            for (String p : parts) {
                if (p == null || p.isEmpty()) {
                    return null;
                }
            }
            return new AiCubeRef(parts[0], parts[1], parts[2], parts[3]);
        }
        if (v instanceof Map<?, ?> m) {
            AiCubeRef ref = new AiCubeRef(
                    str(m.get("connectionName")), str(m.get("catalog")), str(m.get("schema")), str(m.get("cubeName")));
            if (ref.getConnectionName() == null
                    || ref.getCatalog() == null
                    || ref.getSchema() == null
                    || ref.getCubeName() == null) {
                return null;
            }
            return ref;
        }
        return null;
    }

    /**
     * Optional slicer filters, each a {@code {dimension, hierarchy, level, op, members, value, n}} map,
     * mapped to {@link AiFilterSelection}. Absent / non-list returns an empty list.
     */
    @SuppressWarnings("unchecked")
    public static List<AiFilterSelection> readFilters(Object v) {
        List<AiFilterSelection> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            AiFilterSelection f = new AiFilterSelection();
            f.setDimension(str(m.get("dimension")));
            f.setHierarchy(str(m.get("hierarchy")));
            f.setLevel(str(m.get("level")));
            String op = str(m.get("op"));
            if (op != null) {
                f.setOp(op);
            }
            Object members = m.get("members");
            if (members instanceof List<?> ml) {
                List<String> mem = new ArrayList<>();
                for (Object mo : ml) {
                    if (mo != null) {
                        mem.add(mo.toString());
                    }
                }
                f.setMembers(mem);
            }
            f.setValue(str(m.get("value")));
            Double n = readDouble(m.get("n"));
            if (n != null) {
                f.setN(n.intValue());
            }
            out.add(f);
        }
        return out;
    }

    private static String str(Object v) {
        return readString(v);
    }
}
