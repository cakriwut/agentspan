# AgentClient — Control-Plane API Reference

`AgentClient` is the Java SDK's interface to the Agentspan server's proprietary agent control-plane (`/api/agent/*`). Strictly scoped to five endpoints — compile, deploy, start, status, respond. Standard Conductor endpoints (`/api/workflow/*`, `/api/tasks`, etc.) are handled by the Conductor SDK's own typed clients (`WorkflowClient`, `TaskClient`, `MetadataClient`).

Every request goes through the shared `ConductorClient`'s native HTTP + auth + serialization layer. No hand-rolled HTTP. `ConductorClientException` is mapped to agentspan's `AgentAPIException` / `AgentNotFoundException`.

## Methods

| Method | HTTP | Java input type | Java return type | Description |
|---|---|---|---|---|
| [`compileAgent`](#compileagent) | `POST /api/agent/compile` | `AgentRequest` | `CompileResponse` | Compile to Conductor workflow def — no side effects |
| [`deployAgent`](#deployagent) | `POST /api/agent/deploy` | `AgentRequest` | `StartResponse` | Register workflow def without starting |
| [`startAgent`](#startagent) | `POST /api/agent/start` | `AgentRequest` | `StartResponse` | Compile + register + start execution |
| [`getAgentStatus`](#getagentstatus) | `GET /api/agent/{id}/status` | path: `executionId` | `AgentStatusResponse` | Poll execution status; includes HITL pending-tool |
| [`respond`](#respond) | `POST /api/agent/{id}/respond` | `RespondBody` | `void` | Resume a paused HITL task |

---

## AgentRequest

Input to `compileAgent`, `deployAgent`, and `startAgent`. Maps 1-to-1 with the server's `StartRequest` DTO. Built via static factory methods:

```java
// Native agent (model + tools defined in the SDK)
AgentRequest.nativeAgent(serializedAgentMap).build()

// Framework-backed agent (OpenAI, Google ADK, LangChain, Skill)
AgentRequest.frameworkAgent("openai", serializedRawConfig).build()

// With execution fields (for /start only)
AgentRequest.nativeAgent(serializedMap)
    .prompt("What is the capital of France?")
    .sessionId("session-abc")
    .runId("a1b2c3...")          // per-execution domain UUID for stateful agents
    .staticPlan(planMap)         // deterministic PLAN_EXECUTE plan
    .build()
```

**Field mapping to server `StartRequest`:**

| `AgentRequest` field | JSON key | Server `StartRequest` field | Used by |
|---|---|---|---|
| `agentConfig` | `"agentConfig"` | `agentConfig` | compile / deploy / start (native agents) |
| `framework` | `"framework"` | `framework` | compile / deploy / start (framework agents) |
| `rawConfig` | `"rawConfig"` | `rawConfig` | compile / deploy / start (framework agents) |
| `prompt` | `"prompt"` | `prompt` | start only |
| `sessionId` | `"sessionId"` | `sessionId` | start (stateful agents) |
| `runId` | `"runId"` | `runId` | start (stateful isolation domain) |
| `staticPlan` | `"static_plan"` | `staticPlan` (`@JsonProperty("static_plan")`) | start (PLAN_EXECUTE) |
| `media` | `"media"` | `media` | start (multi-modal) |
| `context` | `"context"` | `context` | start |
| `idempotencyKey` | `"idempotencyKey"` | `idempotencyKey` | start |
| `credentials` | `"credentials"` | `credentials` | compile / start |
| `skillRef` | `"skillRef"` | `skillRef` | start (skill framework) |
| `timeoutSeconds` | `"timeoutSeconds"` | `timeoutSeconds` | compile / start |

`@JsonInclude(NON_NULL)` — null fields are omitted from the wire.

**Structural proof — `static_plan` key:**
The server field is named `staticPlan` in Java but annotated `@JsonProperty("static_plan")`. The `AgentRequest.staticPlan` field is annotated `@JsonProperty("static_plan")` identically, so both sides agree on the JSON key.

---

## RespondBody

Input to `respond`. Provides factory methods for the three common patterns; arbitrary extra fields are flattened to the top level via `@JsonAnyGetter`.

```java
RespondBody.approve()                  // { "approved": true }
RespondBody.approve("Looks good")      // { "approved": true, "reason": "Looks good" }
RespondBody.reject("Needs review")     // { "approved": false, "reason": "Needs review" }
RespondBody.of(Map.of("selected", "writer"))  // { "selected": "writer" }  ← MANUAL strategy
```

**Used by `AgentHandle`:**

| `AgentHandle` method | `RespondBody` factory | Wire JSON |
|---|---|---|
| `handle.approve()` | `RespondBody.approve()` | `{ "approved": true }` |
| `handle.approve(comment)` | `RespondBody.approve(comment)` | `{ "approved": true, "reason": "..." }` |
| `handle.reject(reason)` | `RespondBody.reject(reason)` | `{ "approved": false, "reason": "..." }` |
| `handle.respond(map)` | `RespondBody.of(map)` | the map at the top level |

---

## compileAgent

Compile an agent into a Conductor workflow definition. No workflow is registered or executed.

Used by `AgentRuntime.plan(agent)`.

**HTTP:** `POST /api/agent/compile`

### Request body — `AgentRequest`

Native agent wire shape:
```json
{ "agentConfig": { "name": "my_agent", "model": "openai/gpt-4o-mini", "strategy": "handoff", ... } }
```

Framework agent wire shape:
```json
{ "framework": "openai", "rawConfig": { "name": "my_agent", "model": "openai/gpt-4o-mini", "tools": [...] } }
```

### Response — `CompileResponse`

```json
{ "workflowDef": { "name": "my_agent", "version": 1, "tasks": [...] }, "requiredWorkers": ["my_tool_a"] }
```

| Field | Getter | Type | Description |
|---|---|---|---|
| `workflowDef` | `getWorkflowDef()` | `Map<String,Object>` | Full Conductor workflow definition. |
| `requiredWorkers` | `getRequiredWorkers()` | `List<String>` | Task type names the SDK must register local workers for. |

**How the SDK uses it:** `AgentRuntime.plan(agent)` returns the `CompileResponse` directly.

---

## deployAgent

Compile and register the workflow definition on the server without starting an execution. Idempotent.

Used by `AgentRuntime.deploy(Agent...)`.

**HTTP:** `POST /api/agent/deploy`

### Request body — `AgentRequest`

Same as `compileAgent` — agent definition only, no `prompt`.

### Response — `StartResponse`

```json
{ "agentName": "my_agent", "requiredWorkers": ["my_tool_a"] }
```

| Field | Getter | Type | Description |
|---|---|---|---|
| `agentName` | `getAgentName()` | `String` | The registered workflow name on the server. |
| `requiredWorkers` | `getRequiredWorkers()` | `List<String>` | Task type names the SDK must have workers running for. |
| `executionId` | `getExecutionId()` | `String` | Always `null` for deploy — no execution was started. |

**How the SDK uses it:** `AgentRuntime.deploy()` reads `resp.getAgentName()` and wraps it in `DeploymentInfo`.

---

## startAgent

Compile, register, and start a workflow execution in one call.

Used by `AgentRuntime.startAsync(agent, prompt, plan)`.

**HTTP:** `POST /api/agent/start`

### Request body — `AgentRequest`

```json
{
  "agentConfig": { ... },
  "prompt": "What is the capital of France?",
  "sessionId": "session-abc",
  "runId": "a1b2c3d4e5f6...",
  "static_plan": { "steps": [...] }
}
```

### Response — `StartResponse`

```json
{ "executionId": "a3f92b1c-8e4d-4b7a-9c2e-1d5f3a8e6b02", "agentName": "my_agent", "requiredWorkers": ["my_tool_a"] }
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

### Response — `AgentStatusResponse`

```json
{ "executionId": "...", "status": "COMPLETED", "isComplete": true, "isRunning": false, "output": { ... } }
```

HITL paused:
```json
{ "status": "RUNNING", "isWaiting": true, "pendingTool": { "taskRefName": "...", "tool_name": "...", "parameters": { ... } } }
```

**`AgentStatusResponse` fields:**

| JSON field | Getter | Type | Source | Description |
|---|---|---|---|---|
| `executionId` | `getExecutionId()` | `String` | path param | — |
| `status` | `getStatus()` | `String` | `workflow.getStatus().name()` | `RUNNING`, `COMPLETED`, `FAILED`, `TERMINATED`, `TIMED_OUT`, `PAUSED` |
| `isComplete` | `isComplete()` | `boolean` | `workflow.getStatus().isTerminal()` | `true` for all terminal statuses |
| `isRunning` | `isRunning()` | `boolean` | `status == RUNNING` | — |
| `output` | `getOutput()` | `Map<String,Object>` | `workflow.getOutput()` | Only present when `isComplete() == true` |
| `reasonForIncompletion` | `getReasonForIncompletion()` | `String` | `workflow.getReasonForIncompletion()` | Only present on non-COMPLETED terminal status |
| `isWaiting` | `isWaiting()` | `boolean` | HUMAN task IN_PROGRESS | `true` when a HITL task is paused |
| `pendingTool` | `getPendingTool()` | `PendingTool` | HUMAN task inputData | Only when `isWaiting() == true` |

**`PendingTool` fields:**

| JSON field | Getter | Type | Description |
|---|---|---|---|
| `taskRefName` | `getTaskRefName()` | `String` | Conductor task reference name. |
| `tool_name` | `getToolName()` | `String` | Logical tool name shown to the human. |
| `parameters` | `getParameters()` | `Map<String,Object>` | Args the agent passed to the tool. |
| `response_schema` | `getResponseSchema()` | `Object` | JSON Schema the response must conform to (optional). |
| `response_ui_schema` | `getResponseUiSchema()` | `Object` | UI rendering hints (optional). |

---

## respond

Resume a paused HITL execution.

Used by `AgentHandle.approve()`, `.reject()`, `.respond(Map)` and `AgentStream.approve()`, `.reject()`.

**HTTP:** `POST /api/agent/{executionId}/respond`

### Request body — `RespondBody`

```json
{ "approved": true }
{ "approved": false, "reason": "Needs review" }
{ "selected": "writer" }
```

### Response

`void` — returns nothing. Throws `AgentAPIException` if no pending HUMAN task exists.

---

## WorkflowClient usage

Raw workflow data (`GET /api/workflow/{id}`) is fetched via the standard Conductor `WorkflowClient` — not `AgentClient`. `AgentClient` owns only `/api/agent/*`.

`WorkflowClient.getWorkflow(id, true)` is called inside `AgentHandle.buildResult()` **once**, after `getAgentStatus` returns terminal, to walk the typed `Workflow`/`Task` objects and compute:

- **Token usage** — `LLM_CHAT_COMPLETE` task `outputData`: `promptTokens`, `completionTokens`, `tokenUsed` → `AgentResult.getTokenUsage()`
- **Tool calls** — worker tasks whose `referenceTaskName` starts with `call_` → `AgentResult.getToolCalls()`

Fires automatically inside `run()` / `waitForResult()`. Callers never invoke it directly.

---

## AgentConfig (request field)

The agent definition serialized under the `agentConfig` key by `AgentConfigSerializer`.

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
