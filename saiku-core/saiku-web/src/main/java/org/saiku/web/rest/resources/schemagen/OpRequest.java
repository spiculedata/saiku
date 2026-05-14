/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Body of {@code POST /saiku/admin/schema-generator/{sessionId}/ops}. The {@code op} field is a
 * polymorphic {@link SuggestionOp}; Jackson discriminates on the {@code op} property inside the
 * payload (see {@code @JsonTypeInfo} on {@link SuggestionOp}).
 */
public record OpRequest(SuggestionOp op) {}
