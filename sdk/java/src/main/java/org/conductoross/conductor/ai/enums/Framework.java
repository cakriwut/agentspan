// Copyright (c) 2025 Agentspan
// Licensed under the MIT License. See LICENSE file in the project root for details.

package org.conductoross.conductor.ai.enums;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Framework identifiers for agents backed by a third-party agent SDK.
 *
 * <p>The server routes each framework through a matching {@code AgentConfigNormalizer}
 * (e.g. {@code OpenAINormalizer}, {@code GoogleADKNormalizer}) before compilation.
 * Native Agentspan agents have no framework — their config is sent as {@code agentConfig}.
 *
 * @see org.conductoross.conductor.ai.frameworks.OpenAIAgent
 * @see org.conductoross.conductor.ai.frameworks.AdkBridge
 * @see org.conductoross.conductor.ai.skill.Skill
 */
public enum Framework {
    OPENAI("openai"),
    GOOGLE_ADK("google_adk"),
    SKILL("skill");

    private final String wireValue;

    Framework(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The JSON/wire string value sent to and expected by the server. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Return the {@code Framework} matching {@code value}, or empty if
     * {@code value} is null, blank, or not a recognised framework identifier.
     */
    @JsonCreator
    public static Optional<Framework> of(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        for (Framework f : values()) {
            if (f.wireValue.equals(value)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
