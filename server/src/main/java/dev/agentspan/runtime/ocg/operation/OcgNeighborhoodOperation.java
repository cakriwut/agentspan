/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import dev.agentspan.runtime.ocg.OcgProperties;

/** {@code GET /api/v1/graph/neighborhood/{entity_id}?depth=N&limit=M} — graph traversal. */
public final class OcgNeighborhoodOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_NEIGHBORHOOD";
    public static final String NAME = "neighborhood";

    private static final int DEFAULT_DEPTH = 2;
    private static final int DEFAULT_LIMIT = 50;

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public HttpRequest build(OcgProperties properties, Map<String, Object> input) {
        String entityId = OcgInputs.required(input, "entity_id");
        URI uri = OcgUri.forApi(properties)
                .pathSegment("graph", "neighborhood", entityId)
                .queryParam("depth", OcgInputs.intOrDefault(input.get("depth"), DEFAULT_DEPTH))
                .queryParam("limit", OcgInputs.intOrDefault(input.get("limit"), DEFAULT_LIMIT))
                .build()
                .toUri();
        return OcgRequest.get(properties, uri);
    }

    @Override
    public Object project(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return raw;
        return OcgInputs.pick(map, "center", "edges", "neighbors");
    }
}
