/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/** {@code GET /api/v1/entities/{entity_id}} — single entity lookup by id. */
public final class OcgGetEntityOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_GET_ENTITY";
    public static final String NAME = "get_entity";

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public HttpRequest build(OcgTarget target, Map<String, Object> input) {
        String entityId = OcgInputs.required(input, "entity_id");
        URI uri =
                OcgUri.forApi(target).pathSegment("entities", entityId).build().toUri();
        return OcgRequest.get(target, uri);
    }

    @Override
    public Object project(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return raw;
        return OcgInputs.pick(map, "id", "type", "title", "properties");
    }
}
