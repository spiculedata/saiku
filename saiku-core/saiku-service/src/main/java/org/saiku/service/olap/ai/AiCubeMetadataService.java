/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

/**
 * Resolves a cube reference to a typed {@link AiSchema} suitable for
 * {@link AiSchemaConverter} consumption. Implementations typically reflect
 * the live olap4j catalogue (with caching). Phase 1 ships only the
 * interface; an olap4j-backed implementation lands in Phase 2 alongside
 * the {@code GET /ai/cubes} + {@code GET /ai/schema/{cubeId}} endpoints.
 */
public interface AiCubeMetadataService {
    /**
     * @throws AiValidationException with field {@code "cube"} if the
     *         reference doesn't resolve to a live cube.
     */
    AiSchema getSchema(AiCubeRef ref);
}
