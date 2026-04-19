package org.saiku.olap.query2.util;

import java.util.Map;

public class ServiceUtil {

    public static String replaceParameters(String query, Map<String, String> parameters) {
        if (parameters != null) {
            for (String parameter : parameters.keySet()) {
                String value = parameters.get(parameter);
                if (value == null) value = "";
                query = query.replaceAll("(?i)\\$\\{" + parameter + "\\}", value);
            }
        }
        return query;
    }
}
