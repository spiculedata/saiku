/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.Objects;

/**
 * One turn in a natural-language ask conversation.
 *
 * <p>Roles are deliberately restricted to {@code user} and {@code assistant} — the system prompt
 * is controlled by each provider implementation (it carries cube-schema framing the caller should
 * not be able to override).
 */
public record NlAskMessage(Role role, String content) {

    public NlAskMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static NlAskMessage user(String content) {
        return new NlAskMessage(Role.USER, content);
    }

    public static NlAskMessage assistant(String content) {
        return new NlAskMessage(Role.ASSISTANT, content);
    }

    public enum Role {
        USER,
        ASSISTANT;

        public String wireName() {
            return name().toLowerCase();
        }
    }
}
