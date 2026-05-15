/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link AiSchemaConverter} when an AiQueryRequest references a
 * name (cube, dimension, hierarchy, level, member, or measure) that
 * doesn't exist on the live schema. Carries enough structured data that
 * {@code AiQueryResource} can return a 400 JSON body the agent can
 * self-correct against.
 */
public class AiValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;
    private final List<String> available;

    public AiValidationException(String field, String message, List<String> available) {
        super(message);
        this.field = field;
        this.available = available == null ? Collections.emptyList() : List.copyOf(available);
    }

    public String getField() { return field; }
    public List<String> getAvailable() { return available; }
}
