// Copyright (c) 2025 Agentspan
// Licensed under the MIT License. See LICENSE file in the project root for details.

package org.conductoross.conductor.ai.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.conductoross.conductor.ai.enums.AgentStatus;
import org.conductoross.conductor.ai.internal.AgentClient;
import org.conductoross.conductor.ai.internal.AgentStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.client.http.WorkflowClient;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.run.Workflow;

/**
 * A handle to a running agent workflow.
 *
 * <p>Returned by {@link org.conductoross.conductor.ai.AgentRuntime#start(org.conductoross.conductor.ai.Agent, String)}.
 * Allows checking status, interacting with human-in-the-loop pauses, and controlling
 * execution — from any process, even after restarts.
 */
public class AgentHandle {
    private static final Logger logger = LoggerFactory.getLogger(AgentHandle.class);

    private static final long DEFAULT_POLL_INTERVAL_MS = 2000;
    private static final long DEFAULT_TIMEOUT_MS = 600_000; // 10 minutes

    private final String executionId;
    private final AgentClient agentClient;
    private final WorkflowClient workflowClient;

    public AgentHandle(String executionId, AgentClient agentClient, WorkflowClient workflowClient) {
        this.executionId = executionId;
        this.agentClient = agentClient;
        this.workflowClient = workflowClient;
    }

    public String getExecutionId() {
        return executionId;
    }

    /**
     * Poll the server until the agent completes and return the final result.
     *
     * @return the agent result
     * @throws RuntimeException if the agent fails or times out
     */
    public AgentResult waitForResult() {
        return waitForResult(DEFAULT_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Poll the server until the agent completes with explicit timeout.
     *
     * @param timeoutMs       maximum wait time in milliseconds
     * @param pollIntervalMs  polling interval in milliseconds
     * @return the agent result
     */
    // Consecutive poll errors before we escalate from DEBUG→WARN→ERROR logging.
    private static final int POLL_ERROR_WARN_AT = 3;

    private static final int POLL_ERROR_FAIL_AT = 10;

    @SuppressWarnings("unchecked")
    public AgentResult waitForResult(long timeoutMs, long pollIntervalMs) {
        long startTime = System.currentTimeMillis();
        int consecutiveErrors = 0;
        Exception lastError = null;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                AgentStatusResponse status = agentClient.getAgentStatus(executionId);
                consecutiveErrors = 0; // reset on success
                lastError = null;
                String workflowStatus = status.getStatus();

                if (workflowStatus == null) {
                    logger.debug("Waiting for agent {} — status unknown", executionId);
                } else if (isTerminalStatus(workflowStatus)) {
                    return buildResult(status, workflowStatus);
                } else {
                    logger.debug("Waiting for agent {} — status: {}", executionId, workflowStatus);
                }

                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for agent result", e);
            } catch (Exception e) {
                lastError = e;
                consecutiveErrors++;
                if (consecutiveErrors >= POLL_ERROR_FAIL_AT) {
                    // Too many consecutive failures — the server is unhealthy. Surface the error
                    // rather than silently timing out, which hides the root cause for 10 minutes.
                    throw new RuntimeException(
                            "Giving up polling agent " + executionId + " after " + consecutiveErrors
                                    + " consecutive errors (last: " + e.getMessage() + ")",
                            e);
                } else if (consecutiveErrors >= POLL_ERROR_WARN_AT) {
                    logger.warn(
                            "Repeated errors polling agent {} ({} consecutive): {}",
                            executionId,
                            consecutiveErrors,
                            e.getMessage());
                } else {
                    logger.debug("Error polling agent status (attempt {}): {}", consecutiveErrors, e.getMessage());
                }
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for agent result", ie);
                }
            }
        }

        String lastErrorMsg = lastError != null ? " (last poll error: " + lastError.getMessage() + ")" : "";
        throw new RuntimeException("Agent timed out after " + timeoutMs + "ms: " + executionId + lastErrorMsg);
    }

    /**
     * Approve a pending tool call that requires human approval.
     */
    public void approve() {
        agentClient.respond(executionId, approveBody(null));
    }

    /**
     * Reject a pending tool call with an optional reason.
     *
     * @param reason rejection reason
     */
    public void reject(String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("approved", false);
        if (reason != null && !reason.isEmpty()) body.put("reason", reason);
        agentClient.respond(executionId, body);
    }

    /**
     * Send an arbitrary structured response to a waiting workflow.
     *
     * <p>Use this for MANUAL agent selection:
     * <pre>{@code handle.respond(Map.of("selected", "writer")); }</pre>
     *
     * @param data the response payload
     */
    public void respond(Map<String, Object> data) {
        agentClient.respond(executionId, data);
    }

    /**
     * Send a message to a waiting agent.
     *
     * @param message the message to send
     */
    public void send(String message) {
        agentClient.respond(executionId, approveBody(null));
    }

    private static Map<String, Object> approveBody(String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("approved", true);
        if (reason != null && !reason.isEmpty()) body.put("reason", reason);
        return body;
    }

    /**
     * Check whether the workflow is currently paused waiting for human input.
     *
     * @return true if the server reports isWaiting == true
     */
    public boolean isWaiting() {
        try {
            AgentStatusResponse status = agentClient.getAgentStatus(executionId);
            return status.isWaiting();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Poll until the workflow is waiting for human input or reaches a terminal state.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if the workflow is now waiting, false if it completed/failed first
     */
    public boolean waitUntilWaiting(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                AgentStatusResponse status = agentClient.getAgentStatus(executionId);
                if (status.isWaiting()) return true;
                if (status.getStatus() != null && isTerminalStatus(status.getStatus())) return false;
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "TERMINATED".equals(status)
                || "TIMED_OUT".equals(status);
    }

    @SuppressWarnings("unchecked")
    private AgentResult buildResult(AgentStatusResponse statusResponse, String workflowStatus) {
        Object output = statusResponse.getOutput();

        AgentStatus status;
        try {
            status = AgentStatus.valueOf(workflowStatus);
        } catch (IllegalArgumentException e) {
            status = AgentStatus.FAILED;
        }

        String error = null;
        if (status != AgentStatus.COMPLETED) {
            error = statusResponse.getReasonForIncompletion();
        }

        // Normalize output to a map
        if (output == null) {
            output = java.util.Collections.singletonMap("result", (Object) null);
        } else if (!(output instanceof Map)) {
            output = java.util.Collections.singletonMap("result", output);
        }

        // Token usage + tool calls: the server doesn't aggregate either on the
        // workflow status response, but every LLM_CHAT_COMPLETE task carries
        // tokenUsed/promptTokens/completionTokens in its outputData, and every
        // tool-worker SIMPLE task in the workflow corresponds to one LLM tool
        // call. Walk the workflow tasks once and aggregate both.
        // WorkflowClient is the standard Conductor client for /api/workflow/* —
        // no need to go through AgentClient for this standard endpoint.
        TokenUsage tokenUsage = null;
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        try {
            Workflow workflow = workflowClient.getWorkflow(executionId, true);
            List<Task> tasks = workflow != null ? workflow.getTasks() : List.of();
            int promptT = 0, completionT = 0, totalT = 0;
            boolean sawTokens = false;
            for (Task task : tasks) {
                String taskType = task.getTaskType();
                Map<String, Object> outputData = task.getOutputData();

                // LLM task — aggregate tokens
                if ("LLM_CHAT_COMPLETE".equals(taskType) && outputData != null) {
                    promptT += toInt(outputData.get("promptTokens"));
                    completionT += toInt(outputData.get("completionTokens"));
                    totalT += toInt(outputData.get("tokenUsed"));
                    sawTokens = true;
                    continue;
                }

                // Tool worker task — capture name, input args (stripping
                // internal Agentspan context), and output result.
                // referenceTaskName starts with "call_" for LLM-dispatched tool calls.
                String refName = task.getReferenceTaskName();
                if (refName != null && refName.startsWith("call_") && outputData != null) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("name", taskType);
                    Map<String, Object> inputData = task.getInputData();
                    if (inputData != null) {
                        Map<String, Object> cleaned = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : inputData.entrySet()) {
                            String k = e.getKey();
                            if (k.startsWith("_")
                                    || "method".equals(k)
                                    || "__agentspan_ctx__".equals(k)
                                    || "evaluatorType".equals(k)
                                    || "expression".equals(k)
                                    || "ctx".equals(k)
                                    || "workerTag".equals(k)
                                    || "agentConfig".equals(k)) continue;
                            cleaned.put(k, e.getValue());
                        }
                        tc.put("args", cleaned);
                    }
                    tc.put("result", outputData.get("result"));
                    toolCalls.add(tc);
                }
            }
            if (sawTokens) {
                tokenUsage = new TokenUsage(promptT, completionT, totalT);
            }
        } catch (Exception e) {
            logger.debug("Could not extract tokens/toolCalls for {}: {}", executionId, e.getMessage());
        }

        return new AgentResult(output, executionId, status, toolCalls, null, tokenUsage, error);
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "AgentHandle{executionId=" + executionId + "}";
    }
}
