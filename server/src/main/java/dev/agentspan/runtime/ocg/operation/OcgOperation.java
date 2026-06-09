/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.net.http.HttpRequest;
import java.util.Map;

import dev.agentspan.runtime.ocg.OcgProperties;

/**
 * Strategy for a single OCG endpoint. One implementation per {@code OCG_*}
 * task type; each owns the URL/method/body for its endpoint plus the
 * field-projection rule for shrinking the raw response before it reaches
 * the LLM.
 *
 * <p>Implementations are stateless and reused across calls — the per-call
 * inputs flow in via {@link #build}, never via constructors.</p>
 */
public interface OcgOperation {

    /** Conductor task type string this operation registers under (e.g. {@code "OCG_QUERY"}). */
    String taskType();

    /** Short operation name used in logs and the task output's {@code operation} field. */
    String name();

    /**
     * Build the HTTP request for this operation. Implementations should
     * use {@link OcgRequest} and {@link OcgUri} so authentication headers
     * and base-URL handling stay consistent across endpoints.
     */
    HttpRequest build(OcgProperties properties, Map<String, Object> input) throws Exception;

    /**
     * Project the parsed JSON response down to the fields the LLM needs.
     * Default is identity — only implementations that strip noise (e.g.
     * scoring metadata, internal ids) need to override.
     */
    default Object project(Object rawResponse) {
        return rawResponse;
    }
}
