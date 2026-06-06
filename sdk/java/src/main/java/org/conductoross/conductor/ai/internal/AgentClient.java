// Copyright (c) 2025 Agentspan
// Licensed under the MIT License. See LICENSE file in the project root for details.

package org.conductoross.conductor.ai.internal;

import java.util.HashMap;
import java.util.Map;

import org.conductoross.conductor.ai.exceptions.AgentAPIException;
import org.conductoross.conductor.ai.exceptions.AgentNotFoundException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.netflix.conductor.client.exception.ConductorClientException;
import com.netflix.conductor.client.http.ConductorClient;
import com.netflix.conductor.client.http.ConductorClientRequest;
import com.netflix.conductor.client.http.ConductorClientRequest.Method;
import com.netflix.conductor.client.http.ConductorClientResponse;
import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.run.Workflow;

/**
 * Client for agentspan's proprietary agent control-plane ({@code /api/agent/*}),
 * built on the SAME Conductor {@link ConductorClient}/ApiClient as
 * {@code TaskClient}, {@link WorkflowClient} and {@code MetadataClient}.
 *
 * <p>A peer of {@link WorkflowClient}: it accepts a {@link ConductorClient} and
 * issues every request through the SDK's native HTTP + auth + serialization
 * layer ({@link ConductorClientRequest} → {@link ConductorClient#execute}). No
 * hand-rolled HTTP. Conductor's {@link ConductorClientException} is mapped back
 * to agentspan's {@link AgentAPIException}/{@link AgentNotFoundException} so the
 * SDK's error contract is preserved for callers.
 *
 * <p>Paths are relative to the client's base path (the server's {@code /api}
 * root), so {@code "/agent/start"} resolves to {@code /api/agent/start}.
 */
public class AgentClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    protected final ConductorClient client;
    private final WorkflowClient workflowClient;

    public AgentClient(ConductorClient client) {
        this.client = client;
        this.workflowClient = new WorkflowClient(client);
    }

    /** {@code POST /api/agent/compile} — compile agent config to a workflow def. */
    public Map<String, Object> compileAgent(Map<String, Object> payload) {
        return postForMap("/agent/compile", payload);
    }

    /** {@code POST /api/agent/deploy} — compile + register, no execution. */
    public Map<String, Object> deployAgent(Map<String, Object> payload) {
        return postForMap("/agent/deploy", payload);
    }

    /** {@code POST /api/agent/start} — compile + register + start an execution. */
    public Map<String, Object> startAgent(Map<String, Object> payload) {
        return postForMap("/agent/start", payload);
    }

    /** {@code GET /api/agent/{executionId}/status} — fetch execution status. */
    public Map<String, Object> getAgentStatus(String executionId) {
        ConductorClientRequest req = ConductorClientRequest.builder()
                .method(Method.GET)
                .path("/agent/{executionId}/status")
                .addPathParam("executionId", executionId)
                .build();
        return executeForMap(req);
    }

    /** {@code POST /api/agent/{executionId}/respond} — respond to a waiting agent. */
    public void respond(String executionId, Map<String, Object> body) {
        ConductorClientRequest req = ConductorClientRequest.builder()
                .method(Method.POST)
                .path("/agent/{executionId}/respond")
                .addPathParam("executionId", executionId)
                .body(body)
                .build();
        try {
            client.execute(req);
        } catch (ConductorClientException e) {
            throw mapException(e);
        }
    }

    /**
     * Raw workflow data (tasks, domain, run_id) via the Conductor
     * {@link WorkflowClient} ({@code GET /api/workflow/{id}}), converted to the
     * Map shape callers expect.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getWorkflow(String executionId) {
        try {
            Workflow workflow = workflowClient.getWorkflow(executionId, true);
            if (workflow == null) return new HashMap<>();
            return JsonMapper.fromJson(JsonMapper.toJson(workflow), Map.class);
        } catch (ConductorClientException e) {
            throw mapException(e);
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private Map<String, Object> postForMap(String path, Map<String, Object> payload) {
        ConductorClientRequest req = ConductorClientRequest.builder()
                .method(Method.POST)
                .path(path)
                .body(payload)
                .build();
        return executeForMap(req);
    }

    private Map<String, Object> executeForMap(ConductorClientRequest req) {
        try {
            ConductorClientResponse<Map<String, Object>> resp = client.execute(req, MAP_TYPE);
            Map<String, Object> data = resp.getData();
            return data != null ? data : new HashMap<>();
        } catch (ConductorClientException e) {
            throw mapException(e);
        }
    }

    /** Preserve agentspan's typed error contract over Conductor's exception. */
    private static RuntimeException mapException(ConductorClientException e) {
        int status = e.getStatus();
        String body = e.getMessage();
        if (status == 404) {
            return new AgentNotFoundException(status, body);
        }
        return new AgentAPIException(status, body);
    }
}
