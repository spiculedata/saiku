package org.saiku.olap.query2.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;

class TqUtil {

    private static final String FORMULA_BEGIN = "${";
    private static final String FORMULA_END = "}";

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

    public static String replaceParameters(String input, Map<String, String> parameters) throws RuntimeException {
        if (StringUtils.isBlank(input)) return input;
        if (!StringUtils.contains(input, FORMULA_BEGIN)) return input;

        int startIdx = StringUtils.indexOf(input, FORMULA_BEGIN);
        int contentStartIdx = startIdx + FORMULA_BEGIN.length();

        if (startIdx > -1) {
            int contentEndIdx = StringUtils.lastIndexOf(input, FORMULA_END);
            int endIdx = contentEndIdx + FORMULA_END.length();
            if (contentEndIdx >= contentStartIdx) {
                String contents = StringUtils.substring(input, contentStartIdx, contentEndIdx);
                if (parameters.containsKey(contents)) {
                    StringBuilder result = new StringBuilder();
                    result.append(StringUtils.substring(input, 0, startIdx));
                    String value = parameters.get(contents);
                    result.append(value);
                    result.append(StringUtils.substring(input, endIdx, input.length()));

                    return result.toString();

                } else {
                    throw new RuntimeException(
                            "Cannot find value for paramter: " + contents + " in query parameter list!");
                }
            }
        }
        return input;
    }
}
