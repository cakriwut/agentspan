/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.net.http.HttpRequest;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import dev.agentspan.runtime.ocg.OcgProperties;

/** {@code DELETE /api/v1/memories/{key}?agent=...&user=...} — remove a memory by key. */
public final class OcgMemoryDeleteOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_MEMORY_DELETE";
    public static final String NAME = "memory_delete";

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
        String key = OcgInputs.required(input, "key");
        UriComponentsBuilder uri = OcgUri.forApi(properties).pathSegment("memories", key);
        addQueryParamIfPresent(uri, "agent", input.get("agent"));
        addQueryParamIfPresent(uri, "user", input.get("user"));
        return OcgRequest.delete(properties, uri.build().toUri());
    }

    private static void addQueryParamIfPresent(UriComponentsBuilder uri, String name, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (StringUtils.isNotEmpty(s)) {
            uri.queryParam(name, s);
        }
    }
}
