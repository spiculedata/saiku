/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

/**
 * Optional body for {@code POST /saiku/admin/schema-generator/{sessionId}/save}.
 *
 * <p>{@code schemaName} overrides the draft's schema name on persist. When {@code null} the draft
 * name is used as-is.
 */
public record SaveRequest(String schemaName) {}
