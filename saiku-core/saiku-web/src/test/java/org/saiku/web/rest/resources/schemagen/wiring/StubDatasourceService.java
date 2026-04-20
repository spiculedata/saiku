/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen.wiring;

import org.saiku.service.datasource.DatasourceService;

/**
 * Test stand-in for {@code DatasourceService} used by the schemagen wiring smoke test.
 * Zero behaviour — the smoke test only exercises bean instantiation.
 */
public class StubDatasourceService extends DatasourceService {}
