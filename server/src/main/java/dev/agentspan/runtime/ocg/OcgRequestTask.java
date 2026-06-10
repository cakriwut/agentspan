/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import dev.agentspan.runtime.ocg.operation.OcgInputs;
import dev.agentspan.runtime.ocg.operation.OcgOperation;

/**
 * System task that proxies a single OCG (Open Context Graph) operation.
 *
 * <p>This class is the thin orchestrator: enabled-check → send → project →
 * cap → COMPLETED/FAILED. The endpoint-specific work (URL, method, body,
 * field projection) lives in the strategy passed via {@link OcgOperation}.
 * One {@link OcgRequestTask} bean per operation is registered by
 * {@link OcgRequestTaskConfig}; the bean name is the operation's task
 * type, which Conductor's {@code SystemTaskRegistry} dispatches on.</p>
 */
public class OcgRequestTask extends WorkflowSystemTask {

    private static final Logger log = LoggerFactory.getLogger(OcgRequestTask.class);

    private static final String TRUNCATE_MARKER = "...[truncated]";
    private static final int LOG_BODY_LIMIT = 256;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final OcgOperation operation;
    private final OcgProperties properties;
    private final HttpClient httpClient;

    public OcgRequestTask(OcgOperation operation, OcgProperties properties) {
        this(operation, properties, defaultHttpClient());
    }

    /** Visible-for-testing constructor with an injectable {@link HttpClient}. */
    OcgRequestTask(OcgOperation operation, OcgProperties properties, HttpClient httpClient) {
        super(Objects.requireNonNull(operation, "operation").taskType());
        this.operation = operation;
        this.properties = Objects.requireNonNull(properties, "properties");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        log.debug("OcgRequestTask registered (taskType={}, operation={})", operation.taskType(), operation.name());
    }

    @Override
    public void start(WorkflowModel workflow, TaskModel task, WorkflowExecutor executor) {
        if (!properties.isEnabled()) {
            fail(task, "OCG is not configured (agentspan.ocg.url is empty)");
            return;
        }
        try {
            HttpResponse<String> response = send(task);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                fail(
                        task,
                        "OCG " + operation.name() + " returned status " + response.statusCode() + ": "
                                + StringUtils.abbreviate(response.body(), LOG_BODY_LIMIT));
                return;
            }
            complete(task, response.body());
        } catch (InterruptedException e) {
            // Re-flag the interrupt on the current thread so Conductor's
            // executor (and anyone else up the stack) can observe the
            // cancellation. Without this, a cancelled task would silently
            // appear to "fail" without the interrupt ever propagating.
            Thread.currentThread().interrupt();
            fail(task, "OCG " + operation.name() + " was interrupted");
        } catch (IOException | RuntimeException e) {
            fail(task, "OCG " + operation.name() + " failed: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(TaskModel task) throws IOException, InterruptedException {
        Map<String, Object> input = task.getInputData() != null ? task.getInputData() : Map.of();
        HttpRequest request = operation.build(properties, input);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void complete(TaskModel task, String body) throws IOException {
        Object parsed = OcgInputs.parseJsonLenient(body);
        Object projected = operation.project(parsed);
        String serialized = OcgInputs.writeJson(projected);
        // Apache StringUtils.abbreviate with a custom marker preserves the
        // exact total-length contract callers depend on for context-window
        // budgeting; max-width must be ≥ marker length to satisfy its API.
        int cap = Math.max(TRUNCATE_MARKER.length(), properties.getResponseCapChars());
        String capped = StringUtils.abbreviate(serialized, TRUNCATE_MARKER, cap);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", capped);
        output.put("operation", operation.name());
        task.setOutputData(output);
        task.setStatus(TaskModel.Status.COMPLETED);
    }

    private static void fail(TaskModel task, String reason) {
        task.setOutputData(Map.of("error", reason));
        task.setReasonForIncompletion(reason);
        task.setStatus(TaskModel.Status.FAILED);
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
