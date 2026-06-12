/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/** {@code POST /api/v1/memories/{key}/reinforce} — confidence boost on re-observation. */
public final class OcgMemoryReinforceOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_MEMORY_REINFORCE";
    public static final String NAME = "memory_reinforce";

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public HttpRequest build(OcgTarget target, Map<String, Object> input) throws IOException {
        String key = OcgInputs.required(input, "key");
        Map<String, Object> body = OcgInputs.pick(input, "agent", "user", "confidence_boost", "source_ref");
        URI uri = OcgUri.forApi(target)
                .pathSegment("memories", key, "reinforce")
                .build()
                .toUri();
        return OcgRequest.postJson(target, uri, body);
    }
}
