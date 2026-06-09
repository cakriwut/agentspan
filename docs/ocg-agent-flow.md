# OCG Sub-Agent

A built-in retrieval sub-agent that any user's LLM can delegate to mid-loop
when it needs context from the Open Context Graph (OCG) — Slack messages,
Jira tickets, code history, stored memories. Enabled by setting
`OCG_URL`. Disabled by leaving it unset.

The feature is also the **first consumer of a generic
`RegisteredAgent` registry pattern**: any future server-side sub-agent
plugs in as one `@Component` without touching `AgentCompiler`,
`AgentService`, or any per-feature `@PostConstruct` boilerplate.

---

## Setup — integrating OCG with AgentSpan

OCG is fully opt-in. The integration is **two environment variables** the
AgentSpan server reads at startup:

| Env var       | Required?           | What it does                                                                                                  |
| ------------- | ------------------- | ------------------------------------------------------------------------------------------------------------- |
| `OCG_URL`     | **Yes** (to enable) | Base URL of your OCG instance, e.g. `https://dev.orkescontextgraph.io`. If unset or empty, every OCG bean stays out of the Spring context, no `_ocg_agent` workflow is registered, and no user agent gets the auto-injected `ocg_agent` tool. The feature is completely dormant. |
| `OCG_API_KEY` | Yes (if OCG requires auth) | Bearer token sent as `Authorization: Bearer <key>` on every OCG HTTP request. Empty means no auth header — fine for unauthenticated local OCG instances; required for the hosted dev / prod instances. |

### Local dev

When starting the server (via `./gradlew bootRun`, IntelliJ run config,
or `java -jar`):

```bash
export OCG_URL=https://dev.orkescontextgraph.io
export OCG_API_KEY=<your-bearer-token>
export OPENAI_API_KEY=sk-...    # the OCG sub-agent also needs an LLM key
./gradlew bootRun
```

In IntelliJ, add the same three to your Spring Boot run configuration's
**Environment variables** field.

### Docker / production

Pass them through whatever your deployment system uses — docker `-e`,
Kubernetes `env`, Helm values, systemd `EnvironmentFile`, etc. They map
to Spring properties via `application.properties`:

```
agentspan.ocg.url=${OCG_URL:}
agentspan.ocg.api-key=${OCG_API_KEY:}
```

So you can alternatively pass them as Spring properties on the JVM
command line (`-Dagentspan.ocg.url=…`) or via a `SPRING_APPLICATION_JSON`
blob if your platform prefers that.

### Verifying it's enabled

After the server starts with `OCG_URL` set you should see these three
lines in the log:

```
INFO  dev.agentspan.runtime.registry.RegisteredTaskDefsRegistrar — Registered 7 TaskDef(s) from 1 supplier(s)
INFO  dev.agentspan.runtime.registry.RegisteredAgentRegistrar — Registered agent: workflow='_ocg_agent' autoExposeAs='ocg_agent'
INFO  dev.agentspan.runtime.registry.RegisteredAgentRegistrar — Registered 1 server-side agent(s)
```

Two quick HTTP checks:

```bash
# 1. The OCG sub-agent workflow is registered
curl -s http://localhost:6767/api/metadata/workflow/_ocg_agent | jq .name
# → "_ocg_agent"

# 2. The OCG primitive TaskDefs are registered
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:6767/api/metadata/taskdefs/ocg_query
# → 200
```

If `OCG_URL` is unset, both endpoints return 404 — that's the disabled
state.

### Optional tuning knobs

| Property                           | Default              | Effect                                                                       |
| ---------------------------------- | -------------------- | ---------------------------------------------------------------------------- |
| `agentspan.ocg.model`              | `openai/gpt-4o-mini` | LLM the OCG sub-agent uses internally. Override via `OCG_MODEL` env or `-Dagentspan.ocg.model=…`. |
| `agentspan.ocg.response-cap-chars` | `8192`               | Per-call response truncation budget. Raise if your model context allows; lower to save tokens. |

---

## 1. Architecture in one picture

```mermaid
flowchart TB
    subgraph L4["Layer 4 — Generic auto-expose (OCG-agnostic)"]
        AC["AgentCompiler<br/>.mergeAutoExposedTools<br/>.AUTO_EXPOSE_AS_TOOL_METADATA_KEY"]
        RAR["RegisteredAgentRegistrar<br/>(picks up RegisteredAgent beans)"]
        RTR["RegisteredTaskDefsRegistrar<br/>(picks up RegisteredTaskDefs beans)"]
    end
    subgraph L3["Layer 3 — OCG sub-agent definition"]
        ORA["OcgRegisteredAgent<br/>@Component"]
        ORT["OcgRegisteredTaskDefs<br/>@Component"]
        OAF["OcgAgentFactory<br/>(builds the AgentConfig)"]
    end
    subgraph L2["Layer 2 — OCG primitive system tasks"]
        OcgReq["OcgRequestTask × 7<br/>(OCG_QUERY, OCG_GET_ENTITY, …)"]
        Strats["Strategy classes:<br/>OcgQueryOperation,<br/>OcgGetEntityOperation, …"]
    end
    subgraph L1["Layer 1 — Configuration"]
        Props["OcgProperties<br/>(url, apiKey, model, responseCapChars)"]
        Cond["@ConditionalOnExpression<br/>('${agentspan.ocg.url:}'.length() > 0)"]
    end

    L1 --> L2
    L1 --> L3
    L3 --> L4
    L2 -.exposes task types.-> L4
```

Reading bottom-up: each layer is independent of the ones above it.
**Removing OCG entirely means deleting layers 1-3; layer 4 stays generic
and useful for any other server-side sub-agent.**

---

## 2. Startup — the registry does the work

```mermaid
sequenceDiagram
    autonumber
    participant SB as Spring Boot
    participant OcgBeans as OcgRegisteredAgent +<br/>OcgRegisteredTaskDefs<br/>(@Component, gated by url)
    participant TaskCfg as OcgRequestTaskConfig<br/>(gated by url)
    participant TDR as RegisteredTaskDefsRegistrar<br/>(generic)
    participant AR as RegisteredAgentRegistrar<br/>(generic)
    participant AC as AgentCompiler
    participant DAO as MetadataDAO

    SB->>+OcgBeans: instantiate (URL is set)
    deactivate OcgBeans
    SB->>+TaskCfg: instantiate — 7 OCG_* system task beans
    deactivate TaskCfg

    Note over TDR,AR: @DependsOn ensures TaskDefs run first

    SB->>+TDR: @PostConstruct
    TDR->>+OcgBeans: taskDefs()
    OcgBeans-->>-TDR: 7 TaskDefs<br/>(ocg_query, ocg_get_entity, …)
    TDR->>+DAO: updateTaskDef × 7
    DAO-->>-TDR: ok
    deactivate TDR

    SB->>+AR: @PostConstruct
    AR->>+OcgBeans: agentConfig() + autoExpose()
    OcgBeans-->>-AR: AgentConfig("_ocg_agent") +<br/>ExposeAsTool("ocg_agent", "Delegate to…")
    AR->>+AC: compile(AgentConfig)
    AC-->>-AR: WorkflowDef "_ocg_agent"
    AR->>AR: stamp def.metadata[autoExposeAsTool]<br/>= {name, description}
    AR->>+DAO: updateWorkflowDef
    DAO-->>-AR: ok
    deactivate AR
    Note over DAO: _ocg_agent is now dispatchable<br/>AND auto-exposed to every<br/>top-level user agent
```

The registrars know nothing about OCG. They iterate `List<RegisteredAgent>`
and `List<RegisteredTaskDefs>` provided by Spring, run each through a
fixed pipeline (compile + stamp + persist for agents; persist for
TaskDefs), and call it a day. OCG just happens to be the one feature
providing those beans today.

---

## 3. Compile-time merge — how every user agent gets `ocg_agent`

```mermaid
sequenceDiagram
    autonumber
    participant U as Client
    participant AS as AgentService
    participant AC as AgentCompiler
    participant DAO as MetadataDAO

    U->>+AS: POST /api/agent/start {agentConfig, prompt}
    AS->>AS: resolveConfig() — normalize framework
    AS->>+AC: compile(config)

    Note over AC: mergeAutoExposedTools(config)
    AC->>+DAO: getAllWorkflowDefsLatestVersions()
    DAO-->>-AC: every WorkflowDef in the store
    loop for each def
        AC->>AC: readAutoExposeSpec(def)<br/>(returns null unless flagged)
        alt has marker, name != config.name, not duplicate
            AC->>AC: append ToolConfig{<br/>  name: spec.name<br/>  toolType: "agent_tool"<br/>  config.workflowName: def.name<br/>}
        end
    end

    AC->>AC: strategy dispatch (compileSimple / compileWithTools / …)<br/>ocg_agent → SUB_WORKFLOW handler at runtime
    AC-->>-AS: WorkflowDef
    AS->>+DAO: updateWorkflowDef
    DAO-->>-AS: ok
    AS->>+DAO: startWorkflow
    DAO-->>-AS: executionId
    AS-->>-U: 200 {executionId}
```

Guards inside the merger:

| Guard            | Why                                                                |
| ---------------- | ------------------------------------------------------------------ |
| No `MetadataDAO` | Unit tests using `new AgentCompiler()` should still work          |
| Self-recursion   | Re-compiling `_ocg_agent` itself won't inject itself as a tool    |
| Duplicate name   | A caller's explicit declaration wins                              |

---

## 4. Runtime delegation — the nested agent dispatch

```mermaid
sequenceDiagram
    autonumber
    participant MLM as Main agent LLM
    participant Enrich as enrich INLINE<br/>(JS dispatch table)
    participant FORK as FORK_JOIN_DYNAMIC
    participant OA as _ocg_agent<br/>(SUB_WORKFLOW)
    participant OLM as OCG sub-agent LLM
    participant Enrich2 as nested enrich INLINE
    participant ORT as OcgRequestTask
    participant OCG as OCG service<br/>(HTTPS)

    activate MLM
    MLM->>MLM: sees ocg_agent in tool spec list<br/>decides to delegate
    MLM->>+Enrich: toolCalls=[{name:"ocg_agent",args:{...}}]
    Enrich->>Enrich: agentToolCfg["ocg_agent"]<br/>→ {workflowName:"_ocg_agent"}
    Enrich->>+FORK: dynamicTasks=[{type:"SUB_WORKFLOW",<br/>  name:"_ocg_agent", ...}]
    FORK->>+OA: dispatch child workflow

    loop until no more tool calls
        OA->>+OLM: LLM_CHAT_COMPLETE<br/>(OCG system prompt with today's date<br/>+ 7 ocg_* tools)
        OLM-->>-OA: toolCalls=[{name:"ocg_query",<br/>args:{query, max_results, …}}]
        OA->>+Enrich2: enrich runs again<br/>(this workflow's dispatch table)
        Enrich2->>Enrich2: ocgCfg["ocg_query"]<br/>→ {taskType:"OCG_QUERY"}
        Enrich2->>+ORT: OCG_QUERY system task
        deactivate Enrich2
        ORT->>+OCG: POST /api/v1/agent/query<br/>Authorization: Bearer <key>
        OCG-->>-ORT: raw JSON citations
        ORT->>ORT: project fields, cap to responseCapChars
        ORT-->>-OA: result (≤ cap)
    end

    OA-->>-FORK: synthesized prose answer
    FORK-->>-Enrich: child workflow output
    Enrich-->>-MLM: tool result (as if ocg_agent were a function)
    MLM->>MLM: continues conversation with answer<br/>as the latest tool result
    deactivate MLM
```

The same compiled-workflow shape (LLM → enrich → fork → join → loop) runs
at **both** levels — the outer dispatches `SUB_WORKFLOW`, the inner
dispatches `OCG_QUERY` and friends. That's because `_ocg_agent` is just
another `AgentConfig` compiled through the same `AgentCompiler.compile()`
pipeline that produced the user's agent.

---

## 5. The seven OCG operations

All endpoints sit under `${agentspan.ocg.url}/api/v1`. Each is backed by
a strategy class implementing `OcgOperation` (under
`runtime/ocg/operation/`); `OcgRequestTask` is a thin orchestrator that
delegates URL/method/body/projection to the strategy.

| Tool name (LLM-visible) | System task type      | Endpoint                                 | Method   |
| ----------------------- | --------------------- | ---------------------------------------- | -------- |
| `ocg_query`             | `OCG_QUERY`           | `/api/v1/agent/query`                    | `POST`   |
| `ocg_get_entity`        | `OCG_GET_ENTITY`      | `/api/v1/entities/{entity_id}`           | `GET`    |
| `ocg_neighborhood`      | `OCG_NEIGHBORHOOD`    | `/api/v1/graph/neighborhood/{entity_id}` | `GET`    |
| `ocg_code_history`      | `OCG_CODE_HISTORY`    | `/api/v1/code/history/{repo_id}`         | `GET`    |
| `ocg_memory_set`        | `OCG_MEMORY_SET`      | `/api/v1/memories`                       | `POST`   |
| `ocg_memory_reinforce`  | `OCG_MEMORY_REINFORCE`| `/api/v1/memories/{key}/reinforce`       | `POST`   |
| `ocg_memory_delete`     | `OCG_MEMORY_DELETE`   | `/api/v1/memories/{key}`                 | `DELETE` |

---

## 6. Why `@ConditionalOnExpression` instead of `@ConditionalOnProperty`

The OCG `@Component`s use:

```java
@ConditionalOnExpression("'${agentspan.ocg.url:}'.length() > 0")
```

rather than the more obvious `@ConditionalOnProperty(name = "url")`
because Spring's default for the latter is *"present and not equal to
false"* — an empty string satisfies that and would load every OCG bean
even with `OCG_URL` unset. The expression form requires a non-empty
value, which matches the intent.

---

## 7. Adding a new server-side sub-agent

Drop one `@Component`. That's it.

```java
@Component
@ConditionalOnExpression("'${agentspan.myfeature.url:}'.length() > 0")
@RequiredArgsConstructor
public class MyRegisteredAgent implements RegisteredAgent {

    private final MyFeatureProperties properties;

    @Override
    public AgentConfig agentConfig() {
        return MyAgentFactory.build(properties);
    }

    @Override
    public ExposeAsTool autoExpose() {
        return new ExposeAsTool(
                "my_agent",
                "Use this when …");
    }
}
```

If your agent has primitive system tasks that need TaskDef entries
(most pure-LLM sub-agents won't), add one more:

```java
@Component
@ConditionalOnExpression("'${agentspan.myfeature.url:}'.length() > 0")
public class MyRegisteredTaskDefs implements RegisteredTaskDefs {
    @Override
    public List<TaskDef> taskDefs() {
        return List.of(/* … */);
    }
}
```

**No `AgentCompiler` edit. No `AgentService` edit. No
`@PostConstruct registerWorkflow()`. No per-feature service class.** The
generic registrars handle the rest.
