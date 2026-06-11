/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentspan.runtime.ocg.OcgProperties;

/** {@code POST /api/v1/agent/query} — natural-language retrieval over the graph. */
public final class OcgQueryOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_QUERY";
    public static final String NAME = "query";

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public HttpRequest build(OcgProperties properties, Map<String, Object> input) throws IOException {
        Map<String, Object> body =
                OcgInputs.pick(input, "query", "max_results", "traversal_level", "start_time", "end_time");
        URI uri =
                OcgUri.forApi(properties).pathSegment("agent", "query").build().toUri();
        return OcgRequest.postJson(properties, uri, body);
    }

    /**
     * Keep citations (the only piece the LLM acts on) and traversal_results
     * when present; drop scoring metadata, embedding vectors, and any other
     * fields the OCG service may add over time.
     */
    @Override
    public Object project(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return raw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("citations", projectCitations(map.get("citations")));
        if (map.containsKey("traversal_results")) {
            out.put("traversal_results", map.get("traversal_results"));
        }
        return out;
    }

    private static List<Map<String, Object>> projectCitations(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> citation) {
                out.add(OcgInputs.pick(citation, "source_item_id", "title", "container_id", "snippet"));
            }
        }
        return out;
    }
}
