# AgentClient — Control-Plane API Reference

`AgentClient` is the Java SDK's interface to the Agentspan server's proprietary agent control-plane (`/api/agent/*`). It is an internal class built on the same `ConductorClient` as `TaskClient` and `WorkflowClient` — every request goes through the same auth/transport layer.

## Methods

| Method | HTTP | Input | Output | Description |
|---|---|---|---|---|
| [`compileAgent`](#compile) | `POST /api/agent/compile` | `StartRequest` | `CompileResponse` | Compile to workflow def — no side effects |
| [`deployAgent`](#deploy) | `POST /api/agent/deploy` | `StartRequest` | `StartResponse` | Register workflow def without starting |
| [`startAgent`](#start) | `POST /api/agent/start` | `StartRequest` | `StartResponse` | Compile + register + start execution |
| [`getAgentStatus`](#getagentstatus) | `GET /api/agent/{id}/status` | path: `executionId` | status map | Poll execution status; includes HITL pending-tool |
| [`respond`](#respond) | `POST /api/agent/{id}/respond` | free-form map | `204 No Content` | Resume a paused HITL task |

!!! note "Workflow data — `WorkflowClient`, not `AgentClient`"
    Fetching raw workflow data (`GET /api/workflow/{id}`) is done via the standard Conductor `WorkflowClient`, not `AgentClient`. `AgentClient` owns only the agentspan-proprietary `/api/agent/*` endpoints. See [WorkflowClient usage](#workflowclient-usage) below.

---

## compile

Compile an agent definition into a Conductor workflow definition without registering or executing it. Use this to inspect the workflow shape or warm the server cache.

**HTTP:** `POST /api/agent/compile`

### Request body — `StartRequest`

| Field | Type | Required | Description |
|---|---|---|---|
| `agentConfig` | `AgentConfig` | ✅ | Full agent definition (see [AgentConfig](#agentconfig)). |
| `prompt` | `String` | ❌ | Not used at compile time; may be omitted. |
| `framework` | `String` | ❌ | Framework ID for foreign agents: `"openai"`, `"google_adk"`, `"langchain"`, `"langgraph"`. Omit for native agents. |
| `rawConfig` | `Map<String,Object>` | ❌ | Raw framework-specific config. Required when `framework` is set. |
| `credentials` | `List<String>` | ❌ | Credential names to inject into the compile context. |
| `timeoutSeconds` | `Integer` | ❌ | Per-agent timeout override (seconds). |

### Response — `CompileResponse`

```json
{
  "workflowDef": {
    "name": "my_agent",
    "version": 1,
    "tasks": [ ... ],
    "inputParameters": ["prompt", "session_id"],
    "outputParameters": { "output": "${...}" }
  },
  "requiredWorkers": ["my_tool_a", "my_tool_b"]
}
```

| Field | Type | Description |
|---|---|---|
| `workflowDef` | `Map<String,Object>` | The full Conductor workflow definition JSON. |
| `requiredWorkers` | `List<String>` | Task type names the SDK must register local workers for before starting. |

---

## deploy

Compile and register the workflow definition on the server without starting an execution. Idempotent — safe to call on every app startup to ensure the latest definition is registered.

**HTTP:** `POST /api/agent/deploy`

### Request body

Same as [compile](#compile) — `StartRequest`.

### Response — `StartResponse`

```json
{
  "executionId": null,
  "agentName": "my_agent",
  "requiredWorkers": ["my_tool_a", "my_tool_b"]
}
```

| Field | Type | Description |
|---|---|---|
| `executionId` | `String` | Always `null` for deploy (no execution started). |
| `agentName` | `String` | The registered workflow/agent name. |
| `requiredWorkers` | `List<String>` | Task type names the SDK must have workers running for. |

---

## start

Compile, register, and start an execution in one call. The most common operation — what `AgentRuntime.run()` uses.

**HTTP:** `POST /api/agent/start`

### Request body — `StartRequest`

| Field | Type | Required | Description |
|---|---|---|---|
| `agentConfig` | `AgentConfig` | ✅ | Full agent definition. |
| `prompt` | `String` | ✅ | The user's input message. |
| `sessionId` | `String` | ❌ | Session/memory identifier for stateful agents. |
| `media` | `List<String>` | ❌ | Media file URLs or base64 strings attached to the prompt. |
| `context` | `Map<String,Object>` | ❌ | Arbitrary key-value context injected into the workflow input. |
| `credentials` | `List<String>` | ❌ | Credential names the server should inject at runtime. |
| `idempotencyKey` | `String` | ❌ | Client-supplied key; server deduplicates starts with the same key. |
| `framework` | `String` | ❌ | Framework ID for foreign agents. |
| `rawConfig` | `Map<String,Object>` | ❌ | Raw framework-specific config (required when `framework` is set). |
| `skillRef` | `Map<String,Object>` | ❌ | Reference to a server-registered skill package. Used with `framework="skill"`. |
| `timeoutSeconds` | `Integer` | ❌ | Per-execution timeout override (seconds). |
| `runId` | `String` | ❌ | Per-execution isolation UUID for stateful agents. The server maps all worker tasks to this domain so concurrent executions don't steal each other's tasks. |
| `static_plan` | `Map<String,Object>` | ❌ | Deterministic plan for `PLAN_EXECUTE` agents. Bypasses the planner LLM entirely. |

### Response — `StartResponse`

```json
{
  "executionId": "a3f92b1c-8e4d-4b7a-9c2e-1d5f3a8e6b02",
  "agentName": "my_agent",
  "requiredWorkers": ["my_tool_a"]
}
```

| Field | Type | Description |
|---|---|---|
| `executionId` | `String` | Conductor workflow ID. Use this to poll status, stream events, or respond. |
| `agentName` | `String` | The registered workflow/agent name. |
| `requiredWorkers` | `List<String>` | Task type names the SDK must have workers polling before the agent can progress. |

---

## getAgentStatus

Poll the current status of a running or completed execution.

**HTTP:** `GET /api/agent/{executionId}/status`

### Path parameters

| Parameter | Type | Description |
|---|---|---|
| `executionId` | `String` | The execution ID returned by `start`. |

### Response

```json
{
  "executionId": "a3f92b1c-8e4d-4b7a-9c2e-1d5f3a8e6b02",
  "status": "COMPLETED",
  "isComplete": true,
  "isRunning": false,
  "output": {
    "result": "Paris is the capital of France."
  }
}
```

When a human-in-the-loop (`HUMAN` or `PULL_WORKFLOW_MESSAGES`) task is active:

```json
{
  "executionId": "a3f92b1c-...",
  "status": "RUNNING",
  "isComplete": false,
  "isRunning": true,
  "isWaiting": true,
  "pendingTool": {
    "taskRefName": "approve_deployment_ref",
    "tool_name": "approve_deployment",
    "parameters": { "environment": "production", "version": "2.1" },
    "response_schema": { "type": "object", "properties": { "approved": { "type": "boolean" } } }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `executionId` | `String` | Echo of the request path parameter. |
| `status` | `String` | Conductor workflow status: `RUNNING`, `COMPLETED`, `FAILED`, `TERMINATED`, `TIMED_OUT`, `PAUSED`. |
| `isComplete` | `boolean` | `true` when `status` is terminal (`COMPLETED`, `FAILED`, `TERMINATED`, `TIMED_OUT`). |
| `isRunning` | `boolean` | `true` when `status == RUNNING`. |
| `output` | `Map<String,Object>` | Final workflow output. Only present when `isComplete == true`. |
| `reasonForIncompletion` | `String` | Error/termination message. Only present when the run didn't complete successfully. |
| `isWaiting` | `boolean` | `true` when a HITL task is paused waiting for input. |
| `pendingTool` | `Object` | Details of the waiting HITL task. Only present when `isWaiting == true`. |
| `pendingTool.taskRefName` | `String` | Conductor task reference name — passed back in the respond call. |
| `pendingTool.tool_name` | `String` | Logical tool name shown to the human reviewer. |
| `pendingTool.parameters` | `Map<String,Object>` | Arguments the agent passed to the tool. |
| `pendingTool.response_schema` | `Object` | JSON Schema the response body must conform to (optional). |
| `pendingTool.response_ui_schema` | `Object` | UI rendering hints for the approval form (optional). |

---

## respond

Resume a paused HITL (`HUMAN` task) execution by providing the human's response. The body is free-form — it is merged into the task's output data and forwarded to the agent.

**HTTP:** `POST /api/agent/{executionId}/respond`

### Path parameters

| Parameter | Type | Description |
|---|---|---|
| `executionId` | `String` | The execution ID of the waiting run. |

### Request body

Free-form `Map<String,Object>`. Convention for standard approve/reject:

```json
{ "approved": true, "comment": "Looks good" }
```

```json
{ "approved": false, "reason": "Needs more testing" }
```

The exact keys depend on the `response_schema` the tool declared. The SDK sends:

| Field | Type | Description |
|---|---|---|
| `approved` | `boolean` | For approve/reject flows: `true` to continue, `false` to stop. |
| `comment` / `reason` | `String` | Optional human-readable message. |

### Response

`204 No Content` — no response body.

---

## WorkflowClient usage

Raw workflow data is fetched via the standard Conductor `WorkflowClient` — not `AgentClient`. The separation is intentional: `AgentClient` owns the agentspan-proprietary `/api/agent/*` control-plane; workflow reads come from the standard `/api/workflow/*` endpoint which has a first-class typed client.

**HTTP:** `GET /api/workflow/{executionId}` _(standard Conductor endpoint, via `WorkflowClient.getWorkflow(id, true)`)_

### Where and why

`WorkflowClient.getWorkflow` is called inside `AgentHandle.buildResult()` **once**, after `getAgentStatus` returns a terminal status. It walks the full task list to compute two things the `/status` endpoint does not aggregate:

- **Token usage** — sums `promptTokens` / `completionTokens` / `tokenUsed` across every `LLM_CHAT_COMPLETE` task → populates `AgentResult.getTokenUsage()`
- **Tool calls** — collects every worker SIMPLE task whose `referenceTaskName` starts with `call_` → populates `AgentResult.getToolCalls()`

This fires automatically when `run()` / `waitForResult()` returns. Callers never call it directly.

### Response

Full Conductor `Workflow` object serialised to `Map<String,Object>`. Key fields:

```json
{
  "workflowId": "a3f92b1c-...",
  "workflowName": "my_agent",
  "status": "COMPLETED",
  "startTime": 1780743314852,
  "endTime": 1780743319011,
  "input": { "prompt": "Hello", "session_id": "" },
  "output": { "result": "Hello back!" },
  "taskToDomain": { "my_tool_a": "run-uuid-no-dashes" },
  "tasks": [
    {
      "taskType": "LLM_CHAT_COMPLETE",
      "referenceTaskName": "my_agent_llm__1",
      "status": "COMPLETED",
      "scheduledTime": 1780743315000,
      "endTime": 1780743318500,
      "outputData": {
        "promptTokens": 312,
        "completionTokens": 47,
        "tokenUsed": 359
      }
    },
    {
      "taskType": "my_tool_a",
      "referenceTaskName": "call_abc123__1",
      "status": "COMPLETED",
      "inputData": { "query": "hello" },
      "outputData": { "result": "world" }
    }
  ]
}
```

---

## AgentConfig

The agent definition object sent in every request body. Maps directly to `Agent.builder()` in the Java SDK.

| Field | Type | Description |
|---|---|---|
| `name` | `String` | Agent/workflow name. |
| `model` | `String` | `"provider/model"` e.g. `"openai/gpt-4o-mini"`. |
| `instructions` | `String \| Object` | System prompt, or a `PromptTemplateRef` for server-stored prompts. |
| `tools` | `List<ToolConfig>` | Tool definitions (see [ToolConfig](#toolconfig)). |
| `agents` | `List<AgentConfig>` | Sub-agents for multi-agent strategies. |
| `strategy` | `String` | `"handoff"` (default), `"sequential"`, `"parallel"`, `"router"`, `"swarm"`, `"plan_execute"`, `"manual"`. |
| `router` | `AgentConfig \| WorkerRef` | For `"router"` strategy: the agent that selects which sub-agent runs. |
| `guardrails` | `List<GuardrailConfig>` | Input/output guardrail definitions. |
| `maxTurns` | `int` | Default `100`. Maximum agent loop iterations. |
| `maxTokens` | `Integer` | LLM `max_tokens`. |
| `temperature` | `Double` | LLM sampling temperature. |
| `timeoutSeconds` | `int` | Default `0` (server default). Execution timeout. |
| `credentials` | `List<String>` | Credential names injected at runtime. |
| `sessionId` | `String` | Session key for memory/stateful agents. |
| `outputType` | `OutputTypeConfig` | Structured output type definition. |
| `termination` | `TerminationConfig` | Early termination condition. |
| `handoffs` | `List<HandoffConfig>` | Swarm handoff trigger rules. |
| `callbacks` | `List<CallbackConfig>` | Before/after model callback workers. |
| `codeExecution` | `CodeExecutionConfig` | Local code execution settings. |
| `cliConfig` | `CliConfig` | CLI command execution settings. |
| `planner` | `AgentConfig` | `PLAN_EXECUTE`: the agent that produces the plan. |
| `fallback` | `AgentConfig` | `PLAN_EXECUTE`: agent used when the plan fails. |
| `plannerContext` | `List<Map>` | `PLAN_EXECUTE`: text/URL context appended to the planner's prompt. |
| `enablePlanning` | `Boolean` | Prepend a "plan first" preamble to the system prompt (ADK-style). |
| `synthesize` | `Boolean` | Append a synthesis step after parallel sub-agents. |
| `includeContents` | `String` | `"none"` = fresh context for sub-agent; absent = inherit parent context. |
| `baseUrl` | `String` | Per-agent LLM provider base URL override. |
| `metadata` | `Map<String,Object>` | Arbitrary metadata stored with the workflow definition. |
| `framework` | `String` | Framework ID for foreign agents (set by SDK bridges). |

### ToolConfig

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | | Tool name shown to the LLM. |
| `description` | `String` | | Tool description shown to the LLM. |
| `inputSchema` | `Map<String,Object>` | | JSON Schema for the tool's parameters. |
| `outputSchema` | `Map<String,Object>` | | JSON Schema for the tool's return value. |
| `toolType` | `String` | `"worker"` | `"worker"`, `"http"`, `"mcp"`, `"human"`, `"generate_image"`, `"generate_audio"`, `"generate_pdf"`, `"rag_search"`, `"pull_workflow_messages"`. |
| `approvalRequired` | `boolean` | `false` | Pause for human approval before executing. |
| `timeoutSeconds` | `Integer` | | Per-tool execution timeout. |
| `maxCalls` | `Integer` | | Cap on how many times this tool may be called per run. |
| `config` | `Map<String,Object>` | | Type-specific config: `url`/`method`/`headers` for HTTP; `server_url` for MCP; etc. |
| `guardrails` | `List<GuardrailConfig>` | | Tool-level input/output guardrails. |
| `stateful` | `boolean` | `false` | Register worker under a per-execution domain to prevent cross-instance task stealing. |
