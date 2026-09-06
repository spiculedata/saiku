/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.objects;

import org.saiku.service.datasource.JdbcUrlPolicy;

/**
 * Early, friendly rejection of admin-supplied datasource JDBC URLs at the REST edge.
 *
 * <p>saiku#1902: this used to be the <em>only</em> check, it was H2-only, and it lived here in
 * {@code saiku-web} — so the {@code .sds} file-load path in {@code saiku-service} (boot,
 * {@code /discover/refresh}) ran no validation at all and any non-H2 driver gadget went straight to
 * {@code DriverManager}. The policy now lives in {@link JdbcUrlPolicy} (service layer) and is
 * enforced at the connection chokepoint regardless of how the URL arrived; this class is kept as
 * the web-layer façade so the admin form fails fast with the same rules.
 *
 * @see JdbcUrlPolicy
 */
public final class JdbcUrlValidator {

    private JdbcUrlValidator() {}

    /**
     * Validate an admin-supplied JDBC URL or raw {@code location=} value.
     *
     * @param jdbcUrlOrLocation the JDBC URL or connection location (may be a Mondrian/XMLA wrapper)
     * @throws IllegalArgumentException if the URL violates {@link JdbcUrlPolicy}; the message never
     *     echoes the URL
     */
    public static void validate(String jdbcUrlOrLocation) {
        JdbcUrlPolicy.validate(jdbcUrlOrLocation);
    }
}
