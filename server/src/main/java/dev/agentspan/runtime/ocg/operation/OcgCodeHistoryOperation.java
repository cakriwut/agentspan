/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import dev.agentspan.runtime.ocg.OcgProperties;

/** {@code GET /api/v1/code/history/{repo_id}?path=...&limit=N} — file commit history. */
public final class OcgCodeHistoryOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_CODE_HISTORY";
    public static final String NAME = "code_history";

    private static final int DEFAULT_LIMIT = 20;

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
        String repoId = OcgInputs.required(input, "repo_id");
        String path = OcgInputs.required(input, "path");
        URI uri = OcgUri.forApi(properties)
                .pathSegment("code", "history", repoId)
                .queryParam("path", path)
                .queryParam("limit", OcgInputs.intOrDefault(input.get("limit"), DEFAULT_LIMIT))
                .build()
                .toUri();
        return OcgRequest.get(properties, uri);
    }

    @Override
    public Object project(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return raw;
        return OcgInputs.pick(map, "commits", "repo_id", "path");
    }
}
