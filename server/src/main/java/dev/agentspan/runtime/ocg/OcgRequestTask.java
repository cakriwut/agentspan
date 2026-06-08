/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

/**
 * System task that proxies a single OCG (Open Context Graph) operation.
 *
 * <p>One {@link OcgRequestTask} instance is registered per OCG endpoint via
 * {@link OcgRequestTaskConfig}. The task type strings (e.g. {@code OCG_QUERY},
 * {@code OCG_GET_ENTITY}) are stable contracts the OCG sub-agent's tool calls
 * dispatch to.</p>
 *
 * <p>Each {@link #start} call:</p>
 * <ol>
 *   <li>Reads operation-specific arguments from {@code task.inputData}</li>
 *   <li>Issues an HTTP request to OCG (path + method determined by operation)</li>
 *   <li>Projects the response to the fields the LLM actually needs</li>
 *   <li>Caps the JSON-serialized projection to
 *       {@link OcgProperties#getResponseCapChars()} so a 5MB graph traversal
 *       can't blow the model's context window</li>
 * </ol>
 *
 * <p>HTTP I/O uses the same {@link HttpClient} pattern as
 * {@code PlannerContextFetchTask} — synchronous, with sensible timeouts, and
 * a constructor-injection seam for unit testing without the network.</p>
 */
public class OcgRequestTask extends WorkflowSystemTask {

    public static final String OP_QUERY = "query";
    public static final String OP_GET_ENTITY = "get_entity";
    public static final String OP_NEIGHBORHOOD = "neighborhood";
    public static final String OP_CODE_HISTORY = "code_history";
    public static final String OP_MEMORY_SET = "memory_set";
    public static final String OP_MEMORY_REINFORCE = "memory_reinforce";
    public static final String OP_MEMORY_DELETE = "memory_delete";

    private static final Logger logger = LoggerFactory.getLogger(OcgRequestTask.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String TRUNCATE_SUFFIX = "...[truncated]";

    private final String operation;
    private final OcgProperties properties;
    private final HttpClient httpClient;

    public OcgRequestTask(String taskType, String operation, OcgProperties properties) {
        this(
                taskType,
                operation,
                properties,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /** Visible-for-testing constructor with an injectable {@link HttpClient}. */
    OcgRequestTask(String taskType, String operation, OcgProperties properties, HttpClient httpClient) {
        super(taskType);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        logger.debug("OcgRequestTask registered (taskType={}, operation={})", taskType, operation);
    }

    @Override
    public void start(WorkflowModel workflow, TaskModel task, WorkflowExecutor executor) {
        if (!properties.isEnabled()) {
            fail(task, "OCG is not configured (agentspan.ocg.url is empty)");
            return;
        }

        Map<String, Object> input = task.getInputData() == null ? Map.of() : task.getInputData();

        try {
            HttpRequest request = buildRequest(input);
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status < 200 || status >= 300) {
                fail(task, "OCG " + operation + " returned status " + status + ": " + truncateForLog(resp.body()));
                return;
            }
            Object parsed = parseJsonLenient(resp.body());
            Object projected = project(parsed);
            String serialized = MAPPER.writeValueAsString(projected);
            if (serialized.length() > properties.getResponseCapChars()) {
                int cap = Math.max(0, properties.getResponseCapChars() - TRUNCATE_SUFFIX.length());
                serialized = serialized.substring(0, cap) + TRUNCATE_SUFFIX;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", serialized);
            output.put("operation", operation);
            task.setOutputData(output);
            task.setStatus(TaskModel.Status.COMPLETED);
        } catch (Exception e) {
            fail(task, "OCG " + operation + " failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Operation → HTTP request mapping
    // ─────────────────────────────────────────────────────────────────────

    HttpRequest buildRequest(Map<String, Object> input) throws Exception {
        String baseUrl = trimTrailingSlash(properties.getUrl());
        HttpRequest.Builder b = HttpRequest.newBuilder().timeout(READ_TIMEOUT).header("Accept", "application/json");
        if (properties.hasApiKey()) {
            b.header("Authorization", "Bearer " + properties.getApiKey());
        }

        switch (operation) {
            case OP_QUERY -> {
                Map<String, Object> body = new LinkedHashMap<>();
                copyIfPresent(input, body, "query");
                copyIfPresent(input, body, "max_results");
                copyIfPresent(input, body, "traversal_level");
                copyIfPresent(input, body, "start_time");
                copyIfPresent(input, body, "end_time");
                return b.uri(URI.create(baseUrl + "/agent/query"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
            }
            case OP_GET_ENTITY -> {
                String entityId = requiredString(input, "entity_id");
                return b.uri(URI.create(baseUrl + "/entities/" + urlEncode(entityId)))
                        .GET()
                        .build();
            }
            case OP_NEIGHBORHOOD -> {
                String entityId = requiredString(input, "entity_id");
                String query = "?depth=" + intOr(input.get("depth"), 2) + "&limit=" + intOr(input.get("limit"), 50);
                return b.uri(URI.create(baseUrl + "/graph/neighborhood/" + urlEncode(entityId) + query))
                        .GET()
                        .build();
            }
            case OP_CODE_HISTORY -> {
                String repoId = requiredString(input, "repo_id");
                String path = requiredString(input, "path");
                String query = "?path=" + urlEncode(path) + "&limit=" + intOr(input.get("limit"), 20);
                return b.uri(URI.create(baseUrl + "/code/history/" + urlEncode(repoId) + query))
                        .GET()
                        .build();
            }
            case OP_MEMORY_SET -> {
                Map<String, Object> body = new LinkedHashMap<>(input);
                body.remove("__agentspan_ctx__");
                return b.uri(URI.create(baseUrl + "/memories"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
            }
            case OP_MEMORY_REINFORCE -> {
                String key = requiredString(input, "key");
                Map<String, Object> body = new LinkedHashMap<>();
                copyIfPresent(input, body, "agent");
                copyIfPresent(input, body, "user");
                copyIfPresent(input, body, "confidence_boost");
                copyIfPresent(input, body, "source_ref");
                return b.uri(URI.create(baseUrl + "/memories/" + urlEncode(key) + "/reinforce"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
            }
            case OP_MEMORY_DELETE -> {
                String key = requiredString(input, "key");
                String agent = stringOrEmpty(input.get("agent"));
                String user = stringOrEmpty(input.get("user"));
                StringBuilder q = new StringBuilder();
                if (!agent.isEmpty()) q.append("agent=").append(urlEncode(agent));
                if (!user.isEmpty()) {
                    if (q.length() > 0) q.append('&');
                    q.append("user=").append(urlEncode(user));
                }
                String suffix = q.length() > 0 ? "?" + q : "";
                return b.uri(URI.create(baseUrl + "/memories/" + urlEncode(key) + suffix))
                        .DELETE()
                        .build();
            }
            default -> throw new IllegalStateException("Unsupported OCG operation: " + operation);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Response projection (mirrors Python `_project_*` helpers)
    // ─────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    Object project(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return raw;
        Map<String, Object> map = (Map<String, Object>) raw;
        return switch (operation) {
            case OP_QUERY -> projectQuery(map);
            case OP_GET_ENTITY -> projectEntity(map);
            case OP_NEIGHBORHOOD -> projectNeighborhood(map);
            case OP_CODE_HISTORY -> projectCodeHistory(map);
            default -> map; // memory_* — pass through
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectQuery(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object citations = raw.get("citations");
        List<Map<String, Object>> projectedCitations = new ArrayList<>();
        if (citations instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> c) {
                    Map<String, Object> cit = (Map<String, Object>) c;
                    Map<String, Object> projected = new LinkedHashMap<>();
                    copyIfPresent(cit, projected, "source_item_id");
                    copyIfPresent(cit, projected, "title");
                    copyIfPresent(cit, projected, "container_id");
                    copyIfPresent(cit, projected, "snippet");
                    projectedCitations.add(projected);
                }
            }
        }
        out.put("citations", projectedCitations);
        if (raw.containsKey("traversal_results")) {
            out.put("traversal_results", raw.get("traversal_results"));
        }
        return out;
    }

    private Map<String, Object> projectEntity(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(raw, out, "id");
        copyIfPresent(raw, out, "type");
        copyIfPresent(raw, out, "title");
        copyIfPresent(raw, out, "properties");
        return out;
    }

    private Map<String, Object> projectNeighborhood(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(raw, out, "center");
        copyIfPresent(raw, out, "edges");
        copyIfPresent(raw, out, "neighbors");
        return out;
    }

    private Map<String, Object> projectCodeHistory(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(raw, out, "commits");
        copyIfPresent(raw, out, "repo_id");
        copyIfPresent(raw, out, "path");
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key) && src.get(key) != null) {
            dst.put(key, src.get(key));
        }
    }

    private static String requiredString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing or empty required input '" + key + "'");
        }
        return s;
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Object parseJsonLenient(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            // Non-JSON bodies (rare) — surface the raw text under a known key.
            return Map.of("raw", body);
        }
    }

    private static String truncateForLog(String body) {
        if (body == null) return "";
        return body.length() > 256 ? body.substring(0, 256) + "..." : body;
    }

    private static void fail(TaskModel task, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", reason);
        task.setOutputData(out);
        task.setReasonForIncompletion(reason);
        task.setStatus(TaskModel.Status.FAILED);
    }
}
