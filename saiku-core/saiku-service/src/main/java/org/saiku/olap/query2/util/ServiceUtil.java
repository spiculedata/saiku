/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2.util;

import java.util.Map;
import org.saiku.service.util.MdxParameterSubstitutor;

public class ServiceUtil {

    /** Thin wrapper around {@link MdxParameterSubstitutor#substitute} —
     *  kept for source-compat with existing callers in {@code ThinQuery}.
     *  The substitution is now hardened against regex / replacement /
     *  MDX-injection per saiku#780. */
    public static String replaceParameters(String query, Map<String, String> parameters) {
        return MdxParameterSubstitutor.substitute(query, parameters);
    }
}
