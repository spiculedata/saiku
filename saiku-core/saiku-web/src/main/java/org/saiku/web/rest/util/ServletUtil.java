/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class ServletUtil {

    private static final String PREFIX_PARAMETER = "param";

    public static Map<String, String> getParameters(HttpServletRequest req) {
        return getParameters(req, PREFIX_PARAMETER);
    }

    private static Map<String, String> getParameters(HttpServletRequest req, String prefix) {

        Map<String, String> queryParams = new HashMap<>();
        if (req != null) {
            // ... and the query parameters
            // We identify any pathParams starting with "param" as query parameters

            // Single-valued params only: repeated params (?p=a&p=b) collapse to the
            // first value - no caller passes multi-value parameters today.
            Enumeration<String> enumeration = req.getParameterNames();
            while (enumeration.hasMoreElements()) {
                String param = (String) enumeration.nextElement();
                String value = req.getParameter(param);
                if (StringUtils.isNotBlank(prefix)) {
                    if (param.toLowerCase().startsWith(prefix)) {
                        param = param.substring(prefix.length());
                        queryParams.put(param, value);
                    }
                } else {
                    queryParams.put(param, value);
                }
            }
        }
        return queryParams;
    }

    private static Map<String, String> getParameters(MultivaluedMap<String, String> formParams) {
        return getParameters(formParams, PREFIX_PARAMETER);
    }

    private static Map<String, String> getParameters(MultivaluedMap<String, String> formParams, String prefix) {
        Map<String, String> queryParams = new HashMap<>();
        if (formParams != null) {
            for (String key : formParams.keySet()) {
                String param = key;
                String value = formParams.getFirst(key);

                if (StringUtils.isNotBlank(prefix)) {
                    if (param.toLowerCase().startsWith(prefix)) {
                        param = param.substring(prefix.length());
                        queryParams.put(param, value);
                    }
                } else {
                    queryParams.put(param, value);
                }
            }
        }
        return queryParams;
    }

    /** Delegates to the hardened {@link
     *  org.saiku.service.util.MdxParameterSubstitutor#substitute} per
     *  saiku#780 — the previous {@code replaceAll}-based impl was
     *  vulnerable to regex injection in the parameter name and
     *  replacement-string injection in the value, plus the raw MDX-
     *  injection surface in both cases. The new impl rejects values
     *  with MDX-meta characters; callers needing those characters
     *  should switch to the typed olap4j Parameter API (planned
     *  follow-up). */
    private static String replaceParameters(String query, Map<String, String> parameters) {
        return org.saiku.service.util.MdxParameterSubstitutor.substitute(query, parameters);
    }

    public static String replaceParameters(HttpServletRequest req, String query) {
        Map<String, String> queryParams = getParameters(req);
        return replaceParameters(query, queryParams);
    }

    public static String replaceParameters(MultivaluedMap<String, String> formParams, String query) {
        Map<String, String> queryParams = getParameters(formParams);
        return replaceParameters(query, queryParams);
    }
}
