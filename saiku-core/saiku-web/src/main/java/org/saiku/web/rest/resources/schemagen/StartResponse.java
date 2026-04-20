/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

/**
 * Body of {@code POST /saiku/admin/schema-generator/start/{dataSourceId}}.
 *
 * <p>Returned with HTTP 202 Accepted — the pipeline is running asynchronously (from the client's
 * perspective), and the caller polls {@link StatusResponse} for stage transitions.
 */
public record StartResponse(String sessionId, String dataSourceId, String stage) {}
