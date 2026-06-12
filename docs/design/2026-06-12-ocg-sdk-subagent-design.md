# OCG Sub-Agent via SDK — Fully SDK-Defined, Multi-Instance Design

**Date:** 2026-06-12
**Status:** Draft
**Supersedes:** the auto-expose mechanism and server-registered `_ocg_agent` described in `docs/ocg-agent-flow.md`

---

## Overview

Move the OCG sub-agent out of the server entirely and into the Python SDK:

- **The SDK owns the agent definition** — system prompt, model, tool schemas,
  turn limit — via an `ocg_agent()` factory that returns an ordinary `Agent`,
  which users pass to their main agent with the existing `agent_tool()`.
- **The SDK owns the instance binding** — each OCG tool carries the target
  OCG base URL and a **credential-store reference** (never the key itself),
  so different agents can point at different OCG instances (multi-tenancy,
  data residency: a US agent → US graph, a Canada agent → Canada graph).
- **The server owns execution** — the seven `OCG_*` system tasks keep doing
  the HTTP calls, secret resolution, field projection, and response capping.

Deleted outright: auto-expose (`AutoExposedToolsMerger`,
`RegisteredAgent.autoExpose()`, `ExposeAsTool`), the server-registered
`_ocg_agent` workflow, `OcgRegisteredAgent`, `OcgAgentFactory`, and — since
OCG was its only consumer — the `RegisteredAgent` registry
(`RegisteredAgentRegistrar`, `RegisteredAgent`).

## Motivation

- **Explicitness.** Tool injection that never appears in user code is
  surprising in review and hard to test. After this change, an agent's tool
  list in Python is its complete tool list.
- **Multi-instance / multi-tenancy.** A server-wide `OCG_URL` is structurally
  single-instance: no agent definition, wherever it lives, can target a
  second graph. Per-tool instance binding is the only shape that supports
  US/Canada-style sharding and tenant-owned OCG instances.
- **One source of truth.** The previous two-tier design copied the tool
  description and schemas from `OcgAgentFactory` into the SDK. With the
  server-side definition deleted, the SDK copy *is* the definition. The
  "tiers" collapse: customization is just keyword arguments on `ocg_agent()`.
- **Smaller footprint inside orkes-conductor.** AgentSpan is being embedded
  into orkes-conductor as a single app. After this change AgentSpan registers
  no workflows at boot, mutates no agents, and needs no `OCG_MODEL` — the
  host integration shrinks to the task defs plus two *optional* default env
  vars (§5).

---

## 1. SDK API

New module: `sdk/python/src/agentspan/agents/ocg.py`, exported from
`agentspan.agents`.

### `ocg_agent()` — prebuilt retrieval agent

```python
from agentspan.agents import Agent, agent_tool
from agentspan.agents.ocg import ocg_agent

us_retriever = ocg_agent(
    name="ocg_us",
    url="https://us.ocg.example.com",
    credential="OCG_US_KEY",            # credential-store name, never the key
    model="openai/gpt-4o-mini",
)
ca_retriever = ocg_agent(
    name="ocg_canada",
    url="https://ca.ocg.example.com",
    credential="OCG_CA_KEY",
)

us_agent = Agent(name="us_support", model="openai/gpt-4o",
                 tools=[agent_tool(us_retriever)], instructions="...")
ca_agent = Agent(name="ca_support", model="openai/gpt-4o",
                 tools=[agent_tool(ca_retriever)], instructions="...")

# Or: one agent that routes by geography — impossible under auto-expose
router = Agent(
    name="na_support", model="openai/gpt-4o",
    tools=[
        agent_tool(us_retriever, description="Retrieve context for US customers"),
        agent_tool(ca_retriever, description="Retrieve context for Canadian customers"),
    ],
    instructions="...",
)
```

```python
def ocg_agent(
    *,
    name: str = "ocg_agent",
    model: str,                          # required — no silent default (mirrors the old OCG_MODEL fail-fast)
    url: str,                            # required — every tool binds its own instance
    credential: Optional[str] = None,    # credential-store name for the OCG bearer token
    instructions: Optional[str] = None,  # defaults to the canned OCG system prompt
    max_turns: int = 10,
    # tool-subset switches, forwarded to ocg_tools():
    query: bool = True,
    entities: bool = True,               # ocg_get_entity + ocg_neighborhood
    code_history: bool = True,
    memory: bool = True,                 # ocg_memory_set / _reinforce / _delete
) -> Agent:
    return Agent(
        name=name,
        model=model,
        instructions=instructions or OCG_SYSTEM_PROMPT,
        tools=ocg_tools(url=url, credential=credential, query=query,
                        entities=entities, code_history=code_history, memory=memory),
        max_turns=max_turns,
    )
```

The canned `OCG_SYSTEM_PROMPT` and per-tool schemas move verbatim from
`OcgAgentFactory.java` into `ocg.py` — including the behavioral fixes already
shipped there (execution-time "today" anchoring, omit-`end_time`-for-open-ranges
guidance). The SDK becomes their only home.

**Multi-instance naming requirement:** agents pointing at different OCG
instances MUST have distinct `name`s. Child `agent_tool` workflows are
registered by agent name (`AgentService.registerAgentToolWorkflows()` →
`updateWorkflowDef`), so two differently-configured agents both named
`ocg_agent` would overwrite each other's workflow definition. `ocg_agent()`
docstring carries this warning. (This is a general property of inline agent
tools, not OCG-specific.)

### `ocg_tools()` — raw tools for custom retrieval agents

```python
def ocg_tools(
    *,
    url: Optional[str] = None,
    credential: Optional[str] = None,
    query: bool = True,
    entities: bool = True,
    code_history: bool = True,
    memory: bool = True,
) -> List[ToolDef]: ...
```

Returns up to seven `ToolDef`s with `tool_type="ocg_query"` …
`"ocg_memory_delete"` and the canonical input schemas. For users who want
their own prompt/model/composition and attach OCG tools to an agent they
build themselves.

### Instance binding rules (revised 2026-06-12: url required)

- `url` is **required** — every OCG tool binds the instance it talks to.
  There is no server-side default instance (`agentspan.ocg.url`/`api-key`
  were removed by decision the same day: server-wide instance config is
  exactly the single-instance coupling this design exists to kill).
- `credential` names an entry in the credential store; the server resolves
  it at execution time and sends `Authorization: Bearer <resolved>`. The
  secret never appears in Python code, serialized configs, or workflow
  definitions — identical to the `http_tool` `${NAME}` model, and bounded
  the same way (a caller can only name credentials their org has declared).
  Omitting it means an unauthenticated instance.

### Out of scope

- TypeScript SDK parity — follow-up, same wire format. Until then, the
  prompt/schemas live only in the Python SDK; non-SDK clients (REST, UI)
  can inline the canonical agent JSON published in the docs.
- Dynamic per-*request* instance selection (one compiled tool, URL chosen at
  call time). Instances are fixed at agent-definition time; per-request
  routing is expressed as multiple tools (see router example above).

---

## 2. Wire format and server flow

`ocg_agent()` output is wrapped by the existing `agent_tool()`, so the parent
tool serializes with a full inline `agentConfig` — the standard path, nothing
OCG-specific. Each OCG tool inside it serializes as:

```json
{
  "name": "ocg_query",
  "description": "Query the Open Context Graph ...",
  "inputSchema": {"type": "object", "properties": {"query": {...}, ...}, "required": ["query"]},
  "toolType": "ocg_query",
  "config": {
    "url": "https://us.ocg.example.com",
    "credential": "OCG_US_KEY"
  }
}
```

(`config` omitted entirely for default-instance tools. The serializer also
appends `config.credentials = ["OCG_US_KEY"]` — the existing wire key the
server reads to bound credential resolution for the execution token.)

Server flow:

- `registerAgentToolWorkflows()` compiles and registers the retriever child
  workflow at start, exactly as for any inline agent tool
  (`AgentService.java:1119-1163`).
- `ToolCompiler` already maps the seven `ocg_*` tool types to `OCG_*` task
  types (`ToolCompiler.java:136-142`) and already includes `OCG_TOOL_TYPES`
  in `serverSideTypes`. **New:** it gains an `ocgConfig` map (the `httpConfig`
  pattern at `ToolCompiler.java:342-343`) carrying each OCG tool's
  `url`/`credential` through to the task input, with the same credential-
  placeholder escaping applied to HTTP/MCP headers.
- The `OCG_*` operations resolve their target per call:
  1. `url` from task config if present, else `OcgProperties.url`, else the
     task FAILs with a clear message (§3 validation should have caught this
     at start time — the runtime check is the backstop).
  2. Auth: `credential` from task config (resolved via the credential store)
     if present, else `OcgProperties.apiKey`, else no auth header.
- Projection and response capping (`responseCapChars`) are unchanged and
  shared across all instances.

### Fail-fast validation at agent start

In `AgentService.start()` / `startStreaming()`: if any tool (recursively,
including inline agent-tool children) has a type in `OCG_TOOL_TYPES` and
no `config.url` bound, reject the start request:
`"OCG tool 'ocg_query' has no OCG instance bound: set url= on
ocg_agent()/ocg_tools() in the SDK."`

---

## 3. Server changes

### Delete

| Item | Notes |
|---|---|
| `compiler/AutoExposedToolsMerger.java` + tests | Auto-expose is gone entirely — no flag, no shim |
| `registry/RegisteredAgent.java` (incl. `ExposeAsTool`), `registry/RegisteredAgentRegistrar.java` + tests | OCG was the registry's only consumer; no boot-time workflow registration remains |
| `ocg/OcgRegisteredAgent.java` | |
| `ocg/OcgAgentFactory.java` | Prompt + schemas move to the SDK (§1); delete after the SDK copy lands, in the same PR, so there is never zero or two sources of truth |
| Merger/registrar call sites and Spring wiring in the compile path | |
| `agentspan.ocg.model` property + its fail-fast boot check | Model is now a required SDK parameter |

### Modify

| Item | Change |
|---|---|
| `ocg/operation/*` / `OcgRequestTask` | Read `url`/`credential` from task config with fallback to `OcgProperties` (§2); credential resolution via the existing store machinery |
| `OcgRegisteredTaskDefs` + `OcgRequestTaskConfig` | Registration no longer conditional on `OCG_URL` (there may be no global URL). Gate on `agentspan.ocg.enabled` (default `true`); `url`/`api-key` become the optional *default instance* |
| `AgentService` | Add the §2 start-time validation |
| `OcgProperties` | Drop `model`; document `url`/`apiKey` as default-instance only |

### Keep unchanged

- The seven `OCG_*` system task implementations' projection/capping logic.
- `ToolCompiler.TYPE_MAP` `ocg_*` entries — now the cross-repo wire contract
  with the SDK. The seven tool-type strings get a Javadoc note that the SDK
  depends on them; renames are breaking.

### Behavior changes (intentional, breaking)

1. Deployments relying on auto-expose lose the silent `ocg_agent` tool;
   agents add `agent_tool(ocg_agent(model=..., ...))` (one import + one line).
2. The `_ocg_agent` workflow is no longer registered; anything referencing it
   by name breaks.
3. `OCG_MODEL` is removed and ignored.

No deprecation shims. Release notes + `docs/ocg-agent-flow.md` rewrite cover
migration.

---

## 4. Pre-flight, revisited

The original plan's server-side pre-flight was never implemented and stays
that way: "retrieve before the main agent acts" is now expressible in user
space — a sequential pipeline whose first stage is an `ocg_agent()`, or main-
agent instructions to call the retriever first. No server hook.

---

## 5. Orkes-conductor integration

Context: AgentSpan is being embedded into orkes-conductor as a direct
dependency (single app). [orkes-conductor PR #3673](https://github.com/orkes-io/orkes-conductor/pull/3673)
contains the current integration shim.

### Tool resolution in `OrkesLLM`

`OrkesLLM.getToolSpecs()` resolves tools **by name against the Orkes
integration store** — the natural contract for Orkes-native tools, which
*are* integrations/services/task-defs. AgentSpan-compiled tools are
**self-describing** (name, description, `inputSchema` inline in the LLM task
input) and aren't integrations, so the lookup dropped them; `ocg_agent` was
the first casualty, but any SDK-declared `agent_tool`/`http`/`mcp` tool hits
the same wall.

Options considered:

| # | Option | Assessment |
|---|---|---|
| 1 | **Schema-presence pass-through** (shipped in PR #3673): `inputSchema != null` ⇒ return the spec as-is, skip integration resolution | Correct for every tool this design produces — both `agent_tool(ocg_agent(...))` and raw `ocg_*` tools ship inline schemas. Weakness: schema presence is a heuristic; if Orkes-native tools ever populate `inputSchema`, resolution is silently skipped, and name collisions with integrations resolve silently in favor of the inline spec |
| 2 | **Explicit marker on `ToolSpec`** (`selfDescribing`), set by AgentSpan's `ToolCompiler.compileToolSpecs()`, branched on in `OrkesLLM` | **Selected.** The principled contract — intent declared, not inferred. Detailed design below |
| 3 | Register AgentSpan agents as Orkes integrations so name-lookup succeeds | **Rejected.** Abuses integration semantics, org-scoping is unanswerable, doesn't generalize to user-declared tools, and resolution would rewrite the compiled spec |
| 4 | AgentSpan ships its own LLM task type bypassing `OrkesLLM` | **Rejected.** Forks the LLM path inside the product we're embedding into; loses Orkes provider/integration management |

**Decision:** adopt Option 2, with the marker transported in
`ToolSpec.configParams` (an existing `Map<String,Object>` field) rather
than a new model field — a first-class field would require a conductor-oss
release + dependency bump, which we choose not to wait for (decision
2026-06-12). Option 1's heuristic is then replaced outright — no
transition fallback is kept. Verified live: the top-level
`selfDescribing` key is stripped at `ToolSpec` deserialization, while
`configParams.selfDescribing` arrives intact in the bound LLM task input.
The top-level key is still emitted for a possible future first-class
field; nothing depends on it. Implementation detail + paste-ready orkes
changes: [2026-06-12-toolspec-selfdescribing-plan.md](2026-06-12-toolspec-selfdescribing-plan.md).

### Option 2 in detail — `selfDescribing` on `ToolSpec`

**The contract.** `selfDescribing = true` means: *this spec is complete as
delivered — name, description, and `inputSchema` are authoritative; consumers
must hand it to the LLM as-is and must not resolve, enrich, or replace it by
name against integrations, services, or task definitions.* It deliberately
says nothing about provenance ("compiled by AgentSpan") — any future producer
of complete inline specs (e.g. UI-authored tools) may set it, and consumers
other than `OrkesLLM` get the same instruction. Post-call routing of the
LLM's tool-call output stays where it already is: the workflow's tool router
(SWITCH), which never depended on integration resolution.

**Three repos are touched** (the spec travels: AgentSpan compiles tool-spec
maps into the `LLM_CHAT_COMPLETE` task input → Conductor persists them →
`OrkesLLM` deserializes them into `ToolSpec` objects):

1. **conductor-oss/conductor** — owns the model, `ai` module
   (`ai/src/main/java/org/conductoross/conductor/ai/models/ToolSpec.java`,
   a Lombok `@Data` POJO: `name`, `type`, `configParams`,
   `integrationNames`, `description`, `inputSchema`, `outputSchema`).
   Add one field:

   ```java
   /**
    * When true, this spec is complete as delivered: pass it to the LLM
    * as-is. Consumers must not resolve, enrich, or replace it by name
    * against integrations, services, or task definitions. Set by
    * producers that compile full inline tool specs (e.g. AgentSpan).
    */
   private boolean selfDescribing;
   ```

   `boolean` (not `Boolean`): absent on the wire ⇒ `false` ⇒ existing
   name-resolution behavior. Lombok generates `isSelfDescribing()`. No
   custom Jackson annotations needed — the field round-trips through the
   task-input map like every other field, and LLM providers ignore it when
   building their native tool definitions (they read name / description /
   inputSchema only).

2. **agentspan** — the producer. `ToolCompiler.compileToolSpecs()`
   (`ToolCompiler.java:157`) stamps every compiled spec:

   ```java
   spec.put("selfDescribing", true);
   ```

   Unconditional — every AgentSpan tool spec is self-describing by
   construction (the compiler always emits complete name + description +
   `inputSchema`, for all tool types: `agent_tool`, `http`, `mcp`, `ocg_*`,
   worker, …). No per-type logic.

3. **orkes-conductor** — the consumer. `OrkesLLM.getToolSpecs()` replaces
   the PR #3673 heuristic outright:

   ```java
   if (toolSpec.isSelfDescribing()) {
       // Self-describing spec: complete as delivered — hand it to the
       // LLM as-is, never resolve by name.
       return List.of(toolSpec);
   }
   // existing name-based integration resolution below, unchanged
   ```

**Name-collision semantics become defined behavior:** a self-describing tool
whose name matches a registered integration is passed through — the inline
spec wins, by declared intent rather than by accident of schema presence.
Orkes-native tools without the marker resolve exactly as today, even if they
someday carry schemas — schema presence means nothing to `OrkesLLM` once
the marker branch lands.

**Rollout and skew.** In the single app, producer (AgentSpan dependency) and
consumer (`OrkesLLM`) upgrade atomically in one deploy, so the only skew is
*data* skew: `LLM_CHAT_COMPLETE` task inputs compiled before the upgrade but
executed after. Two facts bound it:

- Agent workflow definitions are re-registered on every agent start
  (`AgentService.start()` → `updateWorkflowDef`), so every start after the
  upgrade compiles fresh specs carrying the marker. Only executions already
  mid-flight at deploy time carry markerless specs — those fall through to
  name resolution and are dropped, an accepted, hours-bounded window
  (no fallback is kept; decision 2026-06-12).
- Conductor's `ObjectMapperProvider` ignores unknown JSON properties, so a
  marker-stamped spec deserializes cleanly even against an old `ToolSpec`
  (the marker is simply dropped). The required landing order: conductor-oss
  field → orkes-conductor dependency bump + consumer branch; until both
  land, the PR #3673 heuristic stays in place as the working path.

**Tests** (fail-first per root `CLAUDE.md`):

- agentspan unit: every spec returned by `compileToolSpecs()` — across all
  tool types — carries `selfDescribing: true` (make it fail first by
  asserting a wrong key, e.g. `self_describing`).
- orkes-conductor unit, `OrkesLLM.getToolSpecs()`:
  marked spec → passed through, integration lookup **never invoked**
  (verify on the mocked integration service); unmarked spec (with or
  without schema) → existing resolution path; marked spec whose name
  matches a registered integration → inline spec wins.

### Multi-tenancy fit

Per-tool instance binding is the only option that matches Orkes' tenancy
model: application properties are app-wide and operator-level, while Orkes
**credential stores are org-scoped**. With this design a tenant self-serves —
they store `OCG_US_KEY` in their org's credential store and reference it from
their agent code; no operator config change, no restart, and credential
resolution is bounded to names their org declared. A named-instances-in-
server-config alternative (`agentspan.ocg.instances.us.url=...`) was
considered and rejected: every tenant onboarding would be an operator-level
config change to an app-wide namespace.

### Housekeeping in orkes-conductor (follow-up PR)

- Replace the `application.properties` OCG block from PR #3673:

  ```properties
  # =============================================================================
  # OCG (Open Context Graph) Configuration
  # =============================================================================
  # OCG agents and tools are declared in user code via the AgentSpan SDK
  # (ocg_agent() / ocg_tools()), each binding its own OCG instance URL and
  # credential-store reference. The properties below only configure the
  # OPTIONAL server-wide default instance, used by tools that don't set url=.
  agentspan.ocg.enabled=${OCG_ENABLED:false}
  ```

- Remove `agentspan.ocg.model=${OCG_MODEL:}` and its fail-fast.
- Replace the PR #3673 `OrkesLLM` heuristic with the `selfDescribing` branch
  (no fallback — Option 2 detail above), bumping the conductor-oss
  dependency to the version carrying the `ToolSpec` field.

---

## 6. Implementation stages

Per SDK plan conventions: validation is a separate stage before documentation.

**Stage 1 — Server: per-instance execution + removals**
1. `OcgRequestTask`/operations: per-call `url`/`credential` resolution with
   `OcgProperties` fallback; credential resolved via the existing store path.
2. `ToolCompiler`: `ocgConfig` plumbing with credential-placeholder escaping;
   stamp `selfDescribing: true` on every spec in `compileToolSpecs()` (§5).
3. Task-def registration gated on `agentspan.ocg.enabled`, not `url`.
4. Delete auto-expose + registry + `OcgRegisteredAgent`/`OcgAgentFactory`,
   strip wiring, drop `agentspan.ocg.model`.
5. Add the start-time "no instance" validation.

**Stage 2 — SDK: `agentspan/agents/ocg.py`**
1. `OCG_SYSTEM_PROMPT` + seven tool schemas moved verbatim from
   `OcgAgentFactory`.
2. `ocg_tools()` (instance binding, subset switches, `credential`-without-
   `url` rejection), `ocg_agent()`; export from `agentspan.agents`.

**Stage 3 — Validation** (before any docs)
- Per root `CLAUDE.md`: every test is written first, made to fail (wrong wire
  key, wrong tool-type string, swapped instance URLs), the failure asserted,
  then fixed. No LLM-judged validation in e2e — assert on workflow/tool
  structure and recorded HTTP traffic, not model output quality.
- Server unit: OCG operation hits per-tool URL when configured, falls back to
  `OcgProperties.url`, FAILs cleanly with neither; credential from config
  resolves via the store, falls back to `apiKey`.
- Server unit: compile with OCG enabled → **no** OCG tool appears unless
  declared (inverse of the old merger test); start-time validation rejects
  instanceless OCG tools with the documented message.
- SDK unit: `ocg_agent()` returns an Agent whose tools carry the expected
  `tool_type`s and `config`; `ocg_tools(memory=False)` returns exactly 4
  defs; `credential` without `url` raises; `url=None` emits no `config`.
- Server unit: every spec from `compileToolSpecs()` carries
  `selfDescribing: true` (§5 test list; the orkes-conductor consumer tests
  live in that repo's PR).
- e2e (`sdk/python/e2e/`): **two** stub OCG instances (WireMock or the
  `e2e/ocg` harness); a US agent and a Canada agent each with their own
  `ocg_agent(...)` — assert each retriever's `OCG_QUERY` traffic lands on its
  own stub and only that stub (the multi-tenancy guarantee). Negative e2e:
  agent without OCG tools → no OCG dispatch.

**Stage 4 — Documentation**
- Rewrite `docs/ocg-agent-flow.md` (currently documents auto-expose and the
  boot-registered `_ocg_agent`).
- Python SDK API reference for `ocg_agent()` / `ocg_tools()`, including the
  multi-instance naming requirement and a canonical agent-JSON snippet for
  non-SDK clients.
- Release note for the breaking changes (§3).
- Cross-repo PRs, in strict landing order (§5): conductor-oss
  `ToolSpec.selfDescribing` field → orkes-conductor dependency bump +
  `OrkesLLM` branch + properties housekeeping. The consumer branch cannot
  ship before the field exists (no fallback is kept), and the PR #3673
  heuristic stays in place until it does.
