/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration for the optional OCG (Open Context Graph) sub-agent.
 *
 * <p>When {@code agentspan.ocg.url} is set, the server registers a specialized
 * retrieval sub-agent at startup, exposes seven {@code OCG_*} system tasks
 * that make HTTP calls to OCG with response capping + field projection, and
 * auto-injects {@code _ocg_agent} as an {@code agent_tool} into every
 * top-level agent so the main agent's LLM can decide to delegate to OCG
 * when it needs context.</p>
 */
@Data
@ConfigurationProperties(prefix = "agentspan.ocg")
public class OcgProperties {

    /** Base URL of the OCG service. Empty / null disables the entire OCG feature. */
    private String url;

    /**
     * Bearer token sent on every OCG HTTP request as
     * {@code Authorization: Bearer <apiKey>}. Empty / null means no auth
     * header — useful for local dev against an unauthenticated OCG instance.
     */
    private String apiKey;

    /**
     * Model the OCG sub-agent uses for its own LLM turns. Required when OCG
     * is enabled — no silent default, because the right model here depends
     * on cost, latency, and the OCG corpus the operator is querying. Boot
     * fails fast in {@link OcgAgentFactory#build} when this is blank.
     */
    private String model;

    /**
     * Per-response truncation cap (post-projection, JSON-serialized) for the
     * {@code OCG_*} system tasks. Mirrors the Python reference helper
     * {@code _enforce_response_cap}.
     */
    private int responseCapChars = 8192;

    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
