/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/v1/memories} — create or overwrite a memory.
 *
 * <p>Body is the input map verbatim minus the {@code __agentspan_ctx__}
 * execution-token glob, which is server-side plumbing and should never be
 * forwarded to OCG. Identity projection — OCG's memory response is small
 * enough to surface unchanged.</p>
 */
public final class OcgMemorySetOperation implements OcgOperation {

    public static final String TASK_TYPE = "OCG_MEMORY_SET";
    public static final String NAME = "memory_set";

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
        Map<String, Object> body = new LinkedHashMap<>(input);
        body.remove("__agentspan_ctx__");
        URI uri = OcgUri.forApi(target).pathSegment("memories").build().toUri();
        return OcgRequest.postJson(target, uri, body);
    }
}
