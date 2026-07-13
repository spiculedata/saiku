/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import java.util.List;

/**
 * Typed validation error thrown by {@link OssieAiValidator}. Carries the JSON path of the
 * offending field plus the candidate list the agent should pick from — same shape the MDX AI
 * surface's {@code AiValidationException} uses so the resource layer's error-serialisation code
 * paths look the same.
 */
public class OssieAiValidationException extends RuntimeException {

    private final String field;
    private final List<String> available;

    public OssieAiValidationException(String field, String message, List<String> available) {
        super(message);
        this.field = field;
        this.available = available == null ? List.of() : List.copyOf(available);
    }

    public String getField() {
        return field;
    }

    public List<String> getAvailable() {
        return available;
    }
}
