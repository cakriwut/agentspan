# Agent — Field Reference and Cross-Layer Proof

This page documents every field on `Agent`, the JSON key it produces on the wire, the corresponding server-side `AgentConfig` field it maps to, and its Python SDK equivalent. It is the authoritative source for understanding what the Java `Agent.builder()` actually sends to the server.

---

## Field mapping table

| Java field | Builder method | JSON key | Server `AgentConfig` field | Python field | Notes |
|---|---|---|---|---|---|
| `name` | `name(String)` | `"name"` | `name` | `name` | Required. Pattern: `^[a-zA-Z_][a-zA-Z0-9_-]*$` |
| `model` | `model(String)` | `"model"` | `model` | `model` | `"provider/model"` format. Omitted for external agents. |
| `instructions` | `instructions(String)` | `"instructions"` | `instructions` | `instructions` | Overridden by `instructionsTemplate` if set. |
| `instructionsTemplate` | `instructionsTemplate(PromptTemplate)` | `"instructions"` (structured) | `instructions` | _(server-managed prompt)_ | Emitted as a `PromptTemplateRef` map instead of a string. Takes precedence over plain instructions. |
| `introduction` | `introduction(String)` | `"introduction"` | `introduction` | `introduction` | Prepended before the first user message in multi-agent discussions. |
| `tools` | `tools(ToolDef...)` | `"tools"` | `tools` | `tools` | List of `ToolConfig` maps. Worker tools use `{_worker_ref, description, parameters}` shape for framework agents. |
| `agents` | `agents(Agent...)` | `"agents"` | `agents` | `agents` | Sub-agents; recursive serialization. Strategy must also be set. |
| `strategy` | `strategy(Strategy)` | `"strategy"` | `strategy` | `strategy` | Only emitted when `agents` or `planner`/`fallback` slots are present. Default `HANDOFF`. |
| `router` | `router(Agent)` | `"router"` | `router` | _(callable or Agent)_ | For `ROUTER` strategy. Serialized as nested AgentConfig. |
| `guardrails` | `guardrails(GuardrailDef...)` | `"guardrails"` | `guardrails` | `guardrails` | List of guardrail configs. |
| `maxTurns` | `maxTurns(int)` | `"maxTurns"` | `maxTurns` | `max_turns` | Default 25 in Java builder; server default 100. Only emitted if > 0. |
| `maxTokens` | `maxTokens(int)` | `"maxTokens"` | `maxTokens` | `max_tokens` | Optional LLM token cap. |
| `temperature` | `temperature(double)` | `"temperature"` | `temperature` | `temperature` | Optional. |
| `timeoutSeconds` | `timeoutSeconds(int)` | `"timeoutSeconds"` | `timeoutSeconds` | `timeout_seconds` | Always emitted (including `0`). Default 0 → server applies its own default. |
| `termination` | `termination(TerminationCondition)` | `"termination"` | `termination` | `termination` | `MaxMessageTermination`, `StopMessageTermination`, etc. |
| `outputType` | `outputType(Class<?>)` | `"outputType"` | `outputType` | `output_type` | Structured output class name. |
| `handoffs` | `handoffs(Handoff...)` | `"handoffs"` | `handoffs` | `handoffs` | SWARM handoff triggers: `OnTextMention`, `OnToolResult`, `OnCondition`. |
| `allowedTransitions` | `allowedTransitions(Map)` | `"allowedTransitions"` | `allowedTransitions` | `allowed_transitions` | SWARM: restricts which agents can transfer to which. |
| `credentials` | `credentials(String...)` | `"credentials"` | `credentials` | `credentials` | Credential names fetched from secrets store at runtime. |
| `requiredTools` | `requiredTools(String...)` | `"requiredTools"` | `requiredTools` | `required_tools` | Tool names that must be called during the run. |
| `metadata` | `metadata(Map)` | `"metadata"` | `metadata` | `metadata` | Arbitrary key-value stored with the workflow definition. |
| `synthesize` | `synthesize(boolean)` | `"synthesize"` | `synthesize` | `synthesize` | Only emitted when **false** (default true is assumed by server). |
| `stateful` | `stateful(boolean)` | `"stateful"` | _(not a server AgentConfig field)_ | `stateful` | Triggers per-execution `runId` domain in `AgentRequest`; not part of the compiled agentConfig. |
| `sessionId` | `sessionId(String)` | _(not in agentConfig)_ | _(not a server AgentConfig field)_ | _(execution param)_ | Sent on the `/start` payload, **not** in the compiled agentConfig. |
| `baseUrl` | `baseUrl(String)` | `"baseUrl"` | `baseUrl` | `base_url` | Per-agent LLM provider endpoint override. |
| `includeContents` | `includeContents(String)` | `"includeContents"` | `includeContents` | `include_contents` | `"none"` = fresh context; absent = inherit parent context. |
| `thinkingBudgetTokens` | `thinkingBudgetTokens(int)` | `"thinkingConfig"` (map) | `thinkingConfig` | `thinking_budget_tokens` | Emitted as `{"enabled": true, "budgetTokens": N}`. Anthropic extended thinking only. |
| `enablePlanning` | `enablePlanning(boolean)` | `"enablePlanning"` | `enablePlanning` | `enable_planning` | Prepends a "plan first" preamble to the system prompt (Google ADK style). Unrelated to `PLAN_EXECUTE`. |
| `prefillTools` | `prefillTools(List)` | `"prefillTools"` | `prefillTools` | `prefill_tools` | Tool calls executed before the first LLM turn; results injected as context. |
| `planner` | `planner(Agent)` | `"planner"` | `planner` | `planner` | `PLAN_EXECUTE` named slot. Required when `strategy=PLAN_EXECUTE`. |
| `fallback` | `fallback(Agent)` | `"fallback"` | `fallback` | `fallback` | `PLAN_EXECUTE` fallback agent when the plan can't compile or fails. |
| `fallbackMaxTurns` | `fallbackMaxTurns(int)` | `"fallbackMaxTurns"` | `fallbackMaxTurns` | `fallback_max_turns` | `PLAN_EXECUTE` only. |
| `plannerContext` | `plannerContext(List<Context>)` | `"plannerContext"` | `plannerContext` | `planner_context` | `PLAN_EXECUTE` only. Text/URL context appended to the planner's prompt at runtime. Validated: throws if strategy ≠ `PLAN_EXECUTE`. |
| `localCodeExecution` | `localCodeExecution(boolean)` | `"codeExecution"` (map) | `codeExecution` | `local_code_execution` | Emitted as a structured `CodeExecutionConfig`. Also injects a `run_code` worker tool. |
| `allowedLanguages` | `allowedLanguages(List)` | nested in `"codeExecution"` | `codeExecution.allowedLanguages` | `allowed_languages` | Only meaningful when `localCodeExecution=true`. Default `["python"]`. |
| `codeExecutionTimeout` | `codeExecutionTimeout(int)` | nested in `"codeExecution"` | `codeExecution.timeout` | `code_execution.timeout` | Default 30s. |
| `allowedCommands` | `allowedCommands(List)` | nested in `"codeExecution"` | `codeExecution.allowedCommands` | `allowed_commands` | Shell commands permitted during code execution. |
| `cliConfig` | `cliConfig(CliConfig)` | `"cliConfig"` (map) | `cliConfig` | `cli_config` | Injects a `run_command` worker tool with the CLI config. |
| `gate` | `gate(TextGate)` | `"gate"` (map) | `gate` | `gate` (`TextGate`) | Sequential pipeline gate: emitted as `{"type": "text_contains", "text": ..., "caseSensitive": ...}`. |
| `stopWhenTaskName` | `stopWhen(String)` | `"stopWhen"` (map) | _(worker-based)_ | `stop_when` (callable) | Java stores the task name; emitted as `{"taskName": name}`. Python stores a callable. |
| `callbacks` | `callbacks(CallbackHandler...)` | `"callbacks"` | `callbacks` | `callbacks` | Introspected for `before_model`, `after_model`, `before_agent`, `after_agent` methods. |
| `beforeModelCallback` | `beforeModelCallback(Function)` | → in `"callbacks"` | `callbacks` | `before_model_callback` | Emitted as a callback entry at position `"before_model"`. |
| `afterModelCallback` | `afterModelCallback(Function)` | → in `"callbacks"` | `callbacks` | `after_model_callback` | Emitted as a callback entry at position `"after_model"`. |
| `beforeAgentCallback` | `beforeAgentCallback(Function)` | → in `"callbacks"` | `callbacks` | `before_agent_callback` | Emitted as a callback entry at position `"before_agent"`. |
| `afterAgentCallback` | `afterAgentCallback(Function)` | → in `"callbacks"` | `callbacks` | `after_agent_callback` | Emitted as a callback entry at position `"after_agent"`. |
| `framework` | `framework(String)` | _(dispatch key)_ | _(not a field)_ | _(not a field)_ | Controls serialization path, not emitted as a field. `"skill"`, `"openai"`, `"google_adk"` trigger early-exit serialization routes. |
| `frameworkConfig` | `frameworkConfig(Map)` | _(spread into output)_ | _(not a field)_ | _(not a field)_ | Merged at the top level of the output map for framework agents. |

---

## Fields NOT in the compiled agentConfig

These Java fields exist on `Agent` but are **not** part of the compiled agentConfig sent to the server — they are used as execution parameters or serialization controls:

| Java field | Where it actually goes | Why |
|---|---|---|
| `sessionId` | `AgentRequest.sessionId` (start payload only) | Execution parameter — identifies the conversation session, not the agent definition. |
| `stateful` | Triggers `runId` generation in `AgentRuntime.startAsync` | Controls per-execution domain isolation, not a compilable property. |
| `framework` | Routes to `AgentRequest.frameworkAgent(fw, agent)` | Serialization dispatch key, not a wire field. |
| `frameworkConfig` | Spread into the output map for framework agents | Merged, not emitted under its own key. |
| `beforeModelCallback` | Registered as a Conductor worker task | Function — serialized as a callback task reference, not inline. |
| `afterModelCallback` | Same | Same. |
| `beforeAgentCallback` | Same | Same. |
| `afterAgentCallback` | Same | Same. |

---

## Fields in Python Agent (and server) but not in Java Agent

These are real gaps — Python serializes them to the server, they exist in `AgentConfig`, but the Java `Agent.builder()` does not expose them.

| Python field | JSON key | Server `AgentConfig` field | Notes |
|---|---|---|---|
| `memory` | `"memory"` | `memory` (`MemoryConfig`) | Conversation memory (session messages, max_messages). Java has no memory abstraction. |
| `reasoning_effort` | `"reasoningEffort"` | `reasoningEffort` | OpenAI reasoning models: `"low"`, `"medium"`, `"high"`. |
| `masked_fields` | `"maskedFields"` | `maskedFields` | Field names redacted in execution history and UI. |
| `context_window_budget` | `"contextWindowBudget"` | `contextWindowBudget` | Proactive context condensation threshold (tokens). |
| `dependencies` | _(not serialized)_ | _(no server field)_ | Python allows injecting arbitrary deps into ToolContext. Java uses `ToolContext` directly. |

## Fields in server AgentConfig but in neither Java nor Python Agent

| Server field | Type | Notes |
|---|---|---|
| `description` | `String` | Agent description for UI display. Set by the Agentspan UI/platform, not by SDKs. |

Note: `gate` (`TextGate`) and `enable_planning` exist in **both** Python and Java — they are full equivalents.

---

## Serialization rules

### Strategy emission

Strategy is **only serialized if sub-agents or PLAN_EXECUTE named slots are present**:

```java
boolean hasAgents = agent.getAgents() != null && !agent.getAgents().isEmpty();
boolean hasNamedSlots = agent.getPlanner() != null || agent.getFallback() != null;
if (hasAgents || hasNamedSlots) {
    agentMap.put("strategy", agent.getStrategy().toJsonValue());
}
```

This prevents a bare `Agent.builder().name("x").model("y").build()` from emitting `"strategy": "handoff"` unnecessarily.

### Framework dispatch

`AgentConfigSerializer.serializeAgent()` has three early-exit paths:

| Condition | Output |
|---|---|
| `framework == "skill"` | Emits `{name, model, _framework: "skill", ...frameworkConfig}` |
| `framework == "openai"` or `"google_adk"` | Emits framework-specific map with `_worker_ref` tool shape |
| all others (native) | Emits full AgentConfig map |

`AgentRuntime` routes framework agents via `AgentRequest.frameworkAgent(Framework, Agent)` so the server receives `{framework, rawConfig}` rather than `{agentConfig}`.

### synthesize default

`synthesize` defaults to `true` in the builder. It is **only emitted when `false`** — the server assumes true when the field is absent. Setting `synthesize(true)` explicitly produces no wire output.

### plannerContext validation

`plannerContext` throws `IllegalArgumentException` at `build()` time if set on a non-`PLAN_EXECUTE` agent. This catches misconfiguration before the server can reject it.

### Callbacks

The four function-typed callbacks (`beforeModel`, `afterModel`, `beforeAgent`, `afterAgent`) and the `callbacks: List<CallbackHandler>` field are all serialized into a single `"callbacks"` list on the wire. Each callback becomes a Conductor SIMPLE task reference at the appropriate position. The function objects themselves are never sent — the runtime registers them as local workers.

---

## Defaults

| Field | Java builder default | Server default |
|---|---|---|
| `strategy` | `HANDOFF` | `"handoff"` |
| `maxTurns` | `25` | `100` |
| `timeoutSeconds` | `0` (→ server default) | server-configured |
| `codeExecutionTimeout` | `30` seconds | 30 seconds |
| `synthesize` | `true` | `true` |
| `stateful` | `false` | `false` |
| `enablePlanning` | `false` | `false` |
| `localCodeExecution` | `false` | `false` |
