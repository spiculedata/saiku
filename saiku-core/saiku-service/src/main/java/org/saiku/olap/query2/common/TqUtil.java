package org.saiku.olap.query2.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.saiku.service.util.MdxParameterSubstitutor;

class TqUtil {

    public static List<String> splitParameterValues(String value) {
        List<String> values = new ArrayList<>();
        if (StringUtils.isNotBlank(value)) {
            String[] vs = value.split(",");
            for (String v : vs) {
                v = v.trim();
                values.add(v);
            }
        }
        return values;
    }

    /** Delegates to the hardened {@link MdxParameterSubstitutor} per
     *  saiku#780 — the previous hand-rolled substring scan was
     *  injection-vulnerable for the same reasons as the other two
     *  duplicate impls. */
    public static String replaceParameters(String input, Map<String, String> parameters) throws RuntimeException {
        return MdxParameterSubstitutor.substitute(input, parameters);
    }
}
