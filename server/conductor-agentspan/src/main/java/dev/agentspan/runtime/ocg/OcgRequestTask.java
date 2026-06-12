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
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import dev.agentspan.runtime.ocg.operation.OcgInputs;
import dev.agentspan.runtime.ocg.operation.OcgOperation;
import dev.agentspan.runtime.ocg.operation.OcgTarget;

/**
 * System task that proxies a single OCG (Open Context Graph) operation.
 *
 * <p>This class is the thin orchestrator: resolve target → send → project →
 * cap → COMPLETED/FAILED. The endpoint-specific work (URL, method, body,
 * field projection) lives in the strategy passed via {@link OcgOperation}.
 * One {@link OcgRequestTask} bean per operation is registered by
 * {@link OcgRequestTaskConfig}; the bean name is the operation's task
 * type, which Conductor's {@code SystemTaskRegistry} dispatches on.</p>
 *
 * <p><b>Instance resolution, per call:</b> a tool-bound instance arrives as
 * the reserved {@code __ocg_url} / {@code __ocg_auth} task inputs (compiled
 * from the SDK's {@code url=} / {@code credential=}) — there is no
 * server-side default instance. {@code __ocg_auth}
 * may carry a {@code #{NAME}} placeholder in standalone mode — resolved
 * in-memory via {@link OcgCredentialResolver}, never written back to the
 * task model. The reserved inputs are stripped before the operation sees
 * the input map so they cannot leak into request bodies.</p>
 */
public class OcgRequestTask extends WorkflowSystemTask {

    private static final Logger log = LoggerFactory.getLogger(OcgRequestTask.class);

    /** Reserved task-input key: per-tool OCG base URL. */
    public static final String INPUT_URL = "__ocg_url";

    /** Reserved task-input key: per-tool Authorization header value. */
    public static final String INPUT_AUTH = "__ocg_auth";

    private static final String INPUT_CTX = "__agentspan_ctx__";
    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{[\\w.]+}");

    private static final String TRUNCATE_MARKER = "...[truncated]";
    private static final int LOG_BODY_LIMIT = 256;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final OcgOperation operation;
    private final OcgProperties properties;
    private final HttpClient httpClient;
    private final OcgCredentialResolver credentialResolver;

    public OcgRequestTask(OcgOperation operation, OcgProperties properties, OcgCredentialResolver resolver) {
        this(operation, properties, defaultHttpClient(), resolver);
    }

    /** Visible-for-testing constructor with an injectable {@link HttpClient}. */
    OcgRequestTask(OcgOperation operation, OcgProperties properties, HttpClient httpClient) {
        this(operation, properties, httpClient, null);
    }

    /**
     * Full constructor. {@code credentialResolver} may be null — per-tool
     * {@code #{NAME}} auth placeholders then fail the task instead of
     * leaking unresolved into the Authorization header.
     */
    OcgRequestTask(
            OcgOperation operation,
            OcgProperties properties,
            HttpClient httpClient,
            OcgCredentialResolver credentialResolver) {
        super(Objects.requireNonNull(operation, "operation").taskType());
        this.operation = operation;
        this.properties = Objects.requireNonNull(properties, "properties");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.credentialResolver = credentialResolver;
        log.debug("OcgRequestTask registered (taskType={}, operation={})", operation.taskType(), operation.name());
    }

    @Override
    public void start(WorkflowModel workflow, TaskModel task, WorkflowExecutor executor) {
        Map<String, Object> rawInput = task.getInputData() != null ? task.getInputData() : Map.of();

        String url = stringInput(rawInput.get(INPUT_URL));
        if (url == null) {
            fail(
                    task,
                    "OCG " + operation.name() + " has no OCG instance bound: set url= on "
                            + "ocg_agent()/ocg_tools() in the SDK");
            return;
        }

        String auth = stringInput(rawInput.get(INPUT_AUTH));
        if (auth != null) {
            if (PLACEHOLDER.matcher(auth).find()) {
                String resolved =
                        credentialResolver != null ? credentialResolver.resolve(auth, rawInput.get(INPUT_CTX)) : null;
                if (resolved == null || PLACEHOLDER.matcher(resolved).find()) {
                    fail(
                            task,
                            "OCG " + operation.name() + " credential could not be resolved — check that the "
                                    + "credential name exists in the credential store and the execution "
                                    + "token is valid");
                    return;
                }
                auth = resolved;
            }
        }

        OcgTarget target = new OcgTarget(url, auth);
        try {
            HttpResponse<String> response = send(rawInput, target);
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

    private HttpResponse<String> send(Map<String, Object> rawInput, OcgTarget target)
            throws IOException, InterruptedException {
        // Strip the instance-binding inputs so operations never see them —
        // OcgMemorySetOperation forwards the whole map as the request body.
        Map<String, Object> input = new LinkedHashMap<>(rawInput);
        input.remove(INPUT_URL);
        input.remove(INPUT_AUTH);
        HttpRequest request = operation.build(target, input);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String stringInput(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
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
