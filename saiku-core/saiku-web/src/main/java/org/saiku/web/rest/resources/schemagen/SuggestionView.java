/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import org.saiku.service.schema.generate.enrich.SuggestionSet;

/**
 * Wraps the {@link SuggestionSet} for the {@code /{sessionId}/suggestions} endpoint. A thin shell
 * exists (rather than serialising {@code SuggestionSet} directly) so the endpoint can grow side
 * fields (e.g. rejection reasons) later without breaking the wire format.
 */
public record SuggestionView(SuggestionSet suggestions) {}
