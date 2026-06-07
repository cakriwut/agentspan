# AgentClient — Control-Plane API Reference

`AgentClient` is the Java SDK's interface to the Agentspan server's proprietary agent control-plane (`/api/agent/*`). Strictly scoped to five endpoints — compile, deploy, start, status, respond. Standard Conductor endpoints (`/api/workflow/*`, `/api/tasks`, etc.) are handled by the Conductor SDK's own typed clients (`WorkflowClient`, `TaskClient`, `MetadataClient`).

Every request goes through the shared `ConductorClient`'s native HTTP + auth + serialization layer. No hand-rolled HTTP. `ConductorClientException` is mapped to agentspan's `AgentAPIException` / `AgentNotFoundException`.

## Methods

| Method | HTTP | Input | Java return type | Description |
|---|---|---|---|---|
| [`compileAgent`](#compileagent) | `POST /api/agent/compile` | serialized agent map | `CompileResponse` | Compile to Conductor workflow def — no side effects |
| [`deployAgent`](#deployagent) | `POST /api/agent/deploy` | serialized agent map | `StartResponse` | Register workflow def without starting |
| [`startAgent`](#startagent) | `POST /api/agent/start` | serialized agent map + prompt | `StartResponse` | Compile + register + start execution |
| [`getAgentStatus`](#getagentstatus) | `GET /api/agent/{id}/status` | path: `executionId` | `AgentStatusResponse` | Poll execution status; includes HITL pending-tool |
| [`respond`](#respond) | `POST /api/agent/{id}/respond` | approval/answer map | `void` | Resume a paused HITL task |

---

## compileAgent

Compile an agent into a Conductor workflow definition. No workflow is registered or executed — safe to call to inspect the workflow shape or pre-validate an agent.

Used by `AgentRuntime.plan(agent)`.

**HTTP:** `POST /api/agent/compile`

### Request body

The SDK serializes the `Agent` via `AgentConfigSerializer`. Top-level key is either `agentConfig` (native agents) or `framework` + `rawConfig` (framework-backed agents):

**Native agents:**
```json
{
  "agentConfig": {
    "name": "my_agent",
    "model": "openai/gpt-4o-mini",
    "strategy": "handoff",
    "maxTurns": 25,
    "instructions": "...",
    "tools": [...],
    "agents": [...],
    "credentials": [...],
    "guardrails": [...],
    "timeoutSeconds": 600
  }
}
```

**Framework-backed agents** (`framework="openai"`, `"google_adk"`, `"langchain"`, `"skill"`, etc.):
```json
{
  "framework": "openai",
  "rawConfig": {
    "name": "my_agent",
    "model": "openai/gpt-4o-mini",
    "tools": [...],
    "handoffs": [...]
  }
}
```

### Response — `CompileResponse`

```json
{
  "workflowDef": {
    "name": "my_agent",
    "version": 1,
    "tasks": [...],
    "inputParameters": ["prompt", "session_id"],
    "outputParameters": { "output": "${synthesize_output_ref.output.result}" }
  },
  "requiredWorkers": ["my_tool_a", "my_tool_b"]
}
```

| Field | Getter | Type | Description |
|---|---|---|---|
| `workflowDef` | `getWorkflowDef()` | `Map<String,Object>` | Full Conductor workflow definition. |
| `requiredWorkers` | `getRequiredWorkers()` | `List<String>` | Task type names the SDK must register local workers for. |

**How the SDK uses it:** `AgentRuntime.plan(agent)` returns the `CompileResponse` directly. Callers use `compile.getWorkflowDef()` and `compile.getRequiredWorkers()`.

---

## deployAgent

Compile and register the workflow definition on the server without starting an execution. Idempotent — safe to call on every app startup.

Used by `AgentRuntime.deploy(Agent...)`.

**HTTP:** `POST /api/agent/deploy`

### Request body

Same as [`compileAgent`](#compileagent) — serialized agent map. No `prompt` field.

### Response — `StartResponse`

```json
{
  "agentName": "my_agent",
  "requiredWorkers": ["my_tool_a", "my_tool_b"]
}
```

| Field | Getter | Type | Description |
|---|---|---|---|
| `agentName` | `getAgentName()` | `String` | The registered workflow name on the server. |
| `requiredWorkers` | `getRequiredWorkers()` | `List<String>` | Task type names the SDK must have workers running for. |
| `executionId` | `getExecutionId()` | `String` | Always `null` for deploy — no execution was started. |

**How the SDK uses it:** `AgentRuntime.deploy()` reads `resp.getAgentName()` and wraps it in a `DeploymentInfo(registeredName, agentName)`.

---

## startAgent

Compile, register, and start a workflow execution in one call. The primary operation behind `AgentRuntime.run()`.

Used by `AgentRuntime.startAsync(agent, prompt, plan)`.

**HTTP:** `POST /api/agent/start`

### Request body

Serialized agent map (same as `deployAgent`) plus execution-specific fields:

```json
{
  "agentConfig": { ... },
  "prompt": "What is the capital of France?",
  "sessionId": "session-abc",
  "runId": "a1b2c3d4e5f6...",
  "static_plan": { "steps": [...] }
}
```

| Field | Required | Description |
|---|---|---|
| `agentConfig` / `framework` + `rawConfig` | ✅ | Serialized agent definition. |
| `prompt` | ✅ | User's input message. |
| `sessionId` | ❌ | Session ID for memory / stateful agents. |
| `runId` | ❌ | Per-execution UUID for stateful agents. Server maps all worker tasks to this domain so concurrent executions don't steal each other's tasks. |
| `static_plan` | ❌ | Deterministic plan for `PLAN_EXECUTE` strategy — bypasses the planner LLM. |

### Response — `StartResponse`

```json
{
  "executionId": "a3f92b1c-8e4d-4b7a-9c2e-1d5f3a8e6b02",
  "agentName": "my_agent",
  "requiredWorkers": ["my_tool_a"]
}
```

| Field | Getter | Type | Description |
|---|---|---|---|
| `executionId` | `getExecutionId()` | `String` | Conductor workflow ID. `@JsonAlias` handles legacy keys (`workflowId`, `id`, `correlationId`). |
| `agentName` | `getAgentName()` | `String` | The registered workflow name. |
| `requiredWorkers` | `getRequiredWorkers()` | `List<String>` | Task type names the SDK must have workers polling before the agent can progress. |

**How the SDK uses it:** `AgentRuntime.startAsync()` reads `response.getExecutionId()` and passes it to `new AgentHandle(executionId, agentClient, workflowClient)`.

---

## getAgentStatus

Poll the current status of a running or completed execution.

Used by `AgentHandle.waitForResult()` and `AgentHandle.waitUntilWaiting()`.

**HTTP:** `GET /api/agent/{executionId}/status`

### Path parameters

| Parameter | Type | Description |
|---|---|---|
| `executionId` | `String` | Execution ID returned by `startAgent`. |

### Response — `AgentStatusResponse`

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

When a HITL task (`HUMAN` or `PULL_WORKFLOW_MESSAGES`) is paused:

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

**`AgentStatusResponse` fields:**

| JSON field | Getter | Type | Source | Description |
|---|---|---|---|---|
| `executionId` | `getExecutionId()` | `String` | path param echo | — |
| `status` | `getStatus()` | `String` | `workflow.getStatus().name()` | `RUNNING`, `COMPLETED`, `FAILED`, `TERMINATED`, `TIMED_OUT`, `PAUSED` |
| `isComplete` | `isComplete()` | `boolean` | `workflow.getStatus().isTerminal()` | `true` for all terminal statuses |
| `isRunning` | `isRunning()` | `boolean` | `status == RUNNING` | — |
| `output` | `getOutput()` | `Map<String,Object>` | `workflow.getOutput()` | Only present when `isComplete() == true` |
| `reasonForIncompletion` | `getReasonForIncompletion()` | `String` | `workflow.getReasonForIncompletion()` | Only present on non-COMPLETED terminal status |
| `isWaiting` | `isWaiting()` | `boolean` | HUMAN task IN_PROGRESS | Only present when a HITL task is paused |
| `pendingTool` | `getPendingTool()` | `PendingTool` | HUMAN task inputData | Only present when `isWaiting() == true` |

**`PendingTool` fields:**

| JSON field | Getter | Type | Description |
|---|---|---|---|
| `taskRefName` | `getTaskRefName()` | `String` | Passed back in the `respond` call if needed. |
| `tool_name` | `getToolName()` | `String` | Logical tool name shown to the human reviewer. |
| `parameters` | `getParameters()` | `Map<String,Object>` | Args the agent passed to the tool. |
| `response_schema` | `getResponseSchema()` | `Object` | JSON Schema the response must conform to (optional). |
| `response_ui_schema` | `getResponseUiSchema()` | `Object` | UI rendering hints for the approval form (optional). |

**How the SDK uses it:** `AgentHandle.waitForResult()` calls `status.getStatus()` to detect terminal state, then `buildResult()` reads `status.getOutput()` and `status.getReasonForIncompletion()`.

---

## respond

Resume a paused HITL execution by submitting the human's response. Finds the in-progress `HUMAN` task, merges the body into its output data, and marks it `COMPLETED`.

Used by `AgentHandle.approve()`, `AgentHandle.reject()`, `AgentHandle.respond(Map)`.

**HTTP:** `POST /api/agent/{executionId}/respond`

### Path parameters

| Parameter | Type | Description |
|---|---|---|
| `executionId` | `String` | Execution ID of the waiting run. |

### Request body

Free-form `Map<String,Object>` merged into the pending HUMAN task's output data:

```json
{ "approved": true, "comment": "Looks good" }
```

```json
{ "approved": false, "reason": "Needs more testing" }
```

| SDK method | Body sent |
|---|---|
| `handle.approve()` | `{ "approved": true }` |
| `handle.approve(comment)` | `{ "approved": true, "reason": comment }` |
| `handle.reject(reason)` | `{ "approved": false, "reason": reason }` |
| `handle.respond(map)` | the map as-is (for MANUAL strategy selection, etc.) |

### Response

`void` — returns nothing. Throws `AgentAPIException` if no pending HUMAN task exists.

---

## WorkflowClient usage

Raw workflow data (`GET /api/workflow/{id}`) is fetched via the standard Conductor `WorkflowClient` — not `AgentClient`. The separation is intentional: `AgentClient` owns only the agentspan-proprietary `/api/agent/*` endpoints.

**HTTP:** `GET /api/workflow/{executionId}` via `WorkflowClient.getWorkflow(id, true)`

`WorkflowClient.getWorkflow` is called inside `AgentHandle.buildResult()` **once**, after `getAgentStatus` returns a terminal status. It returns a typed `Workflow` object and its `List<Task>` are walked to compute:

- **Token usage** — sums `promptTokens` / `completionTokens` / `tokenUsed` from every `LLM_CHAT_COMPLETE` task → `AgentResult.getTokenUsage()`
- **Tool calls** — every worker task whose `referenceTaskName` starts with `call_` → `AgentResult.getToolCalls()`

This fires automatically when `run()` / `waitForResult()` returns. Callers never invoke it directly.

---

## AgentConfig (request field)

The agent definition nested under the `agentConfig` key in every request body.

| Field | Type | Description |
|---|---|---|
| `name` | `String` | Agent/workflow name. |
| `model` | `String` | `"provider/model"` e.g. `"openai/gpt-4o-mini"`. |
| `instructions` | `String \| Object` | System prompt or `PromptTemplateRef`. |
| `tools` | `List<ToolConfig>` | Tool definitions. |
| `agents` | `List<AgentConfig>` | Sub-agents (for multi-agent strategies). |
| `strategy` | `String` | `"handoff"` (default), `"sequential"`, `"parallel"`, `"router"`, `"swarm"`, `"plan_execute"`, `"manual"`. |
| `router` | `AgentConfig \| WorkerRef` | For `"router"` strategy. |
| `guardrails` | `List<GuardrailConfig>` | Input/output guardrails. |
| `maxTurns` | `int` | Default `100`. |
| `maxTokens` | `Integer` | LLM `max_tokens`. |
| `temperature` | `Double` | LLM temperature. |
| `timeoutSeconds` | `int` | Execution timeout. |
| `credentials` | `List<String>` | Credential names injected at runtime. |
| `outputType` | `OutputTypeConfig` | Structured output definition. |
| `termination` | `TerminationConfig` | Early termination condition. |
| `handoffs` | `List<HandoffConfig>` | Swarm handoff triggers. |
| `callbacks` | `List<CallbackConfig>` | Before/after model callbacks. |
| `codeExecution` | `CodeExecutionConfig` | Local code execution settings. |
| `cliConfig` | `CliConfig` | CLI command execution settings. |
| `planner` | `AgentConfig` | `PLAN_EXECUTE`: agent that produces the plan. |
| `fallback` | `AgentConfig` | `PLAN_EXECUTE`: agent used when the plan fails. |
| `plannerContext` | `List<Map>` | `PLAN_EXECUTE`: text/URL context appended to the planner's prompt. |
| `synthesize` | `Boolean` | Append a synthesis step after parallel sub-agents. |
| `includeContents` | `String` | `"none"` = fresh context; absent = inherit parent context. |
| `baseUrl` | `String` | Per-agent LLM provider base URL override. |
| `metadata` | `Map<String,Object>` | Arbitrary metadata stored with the workflow definition. |
| `framework` | `String` | Framework ID — set by SDK bridges, not by callers directly. |

### ToolConfig

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | | Tool name shown to the LLM. |
| `description` | `String` | | Tool description. |
| `inputSchema` | `Map<String,Object>` | | JSON Schema for tool parameters. |
| `outputSchema` | `Map<String,Object>` | | JSON Schema for tool return value. |
| `toolType` | `String` | `"worker"` | `"worker"`, `"http"`, `"mcp"`, `"human"`, `"generate_image"`, `"generate_audio"`, `"generate_pdf"`, `"rag_search"`, `"pull_workflow_messages"`. |
| `approvalRequired` | `boolean` | `false` | Pause for human approval before executing. |
| `timeoutSeconds` | `Integer` | | Per-tool execution timeout. |
| `maxCalls` | `Integer` | | Maximum invocations per run. |
| `config` | `Map<String,Object>` | | Type-specific config: `url`/`method`/`headers` for HTTP; `server_url` for MCP. |
| `guardrails` | `List<GuardrailConfig>` | | Tool-level guardrails. |
| `stateful` | `boolean` | `false` | Register worker under a per-execution domain (prevents cross-instance task stealing). |
