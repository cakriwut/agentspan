# OCG SDK Sub-Agent — Implementation Status

**Date:** 2026-06-12
**Design:** [2026-06-12-ocg-sdk-subagent-design.md](2026-06-12-ocg-sdk-subagent-design.md)

---

## Accomplished

### Server — Stage 1 (complete)

**Per-instance execution:**
- `OcgTarget` record (`ocg/operation/OcgTarget.java`) — the resolved
  instance (base URL + Authorization header) an operation runs against.
  All seven operations, `OcgRequest`, and `OcgUri` now take `OcgTarget`
  instead of `OcgProperties`; operations can no longer reach the default
  config.
- `OcgRequestTask` resolves the target per call: reserved task inputs
  `__ocg_url` / `__ocg_auth` (compiled from the SDK's `url=` /
  `credential=`) win over the `OcgProperties` default; with neither, the
  task fails with the documented "no OCG instance" message. Reserved keys
  are stripped before operations see the input (so `ocg_memory_set` can't
  leak them into request bodies).
- Credential resolution: `OcgCredentialResolver` +
  `PlaceholderCredentialResolver` resolve `#{NAME}` placeholders through
  the credential store scoped by the execution token (same contract as
  `CredentialAwareHttpTask`); resolved values exist only in memory. In
  embedded mode the host resolves `${workflow.secrets.NAME}` before the
  task starts. An unresolvable placeholder fails the task — it is never
  sent as a bearer token.
- `ToolCompiler.buildEnrichTask` bakes `url` + escaped auth placeholder
  into the `ocgConfig` entry; the enrich script (both copies in
  `JavaScriptBuilder`) merges them into dispatched task inputs.

**`selfDescribing` marker (agentspan's share of OrkesLLM Option 2):**
- `ToolCompiler.compileToolSpecs()` stamps every compiled spec twice:
  top-level `selfDescribing: true` (future first-class field) AND
  `configParams.selfDescribing: true` — the copy that survives `ToolSpec`
  deserialization today (verified live). Merged into existing MCP/API
  `configParams`, never clobbering them.

**Auto-expose + registered-agent machinery deleted:**
- Deleted: `AutoExposedToolsMerger`, `RegisteredAgent` (+ `ExposeAsTool`),
  `RegisteredAgentRegistrar`, `OcgRegisteredAgent`, `OcgAgentFactory`
  (prompt/schemas moved verbatim to the SDK), and their tests
  (`AutoExposedToolsMergeTest`, `RegisteredAgentBootstrapTest`).
- `AgentCompiler.compile()` is the single entry point;
  `compileWithoutAutoExpose` removed (callers updated, incl.
  `MultiAgentCompiler`). `RegisteredTaskDefs` / `RegisteredTaskDefsRegistrar`
  remain (the task-def half of the registry is still used).

**Gating + config:**
- `OcgRequestTaskConfig` and `OcgRegisteredTaskDefs` now gate on
  `agentspan.ocg.enabled` (default `true`), not on `OCG_URL` — tasks must
  exist even with no default instance.
- `OcgProperties`: `model` removed; `enabled` added; `isEnabled()` renamed
  `hasDefaultUrl()` (the Lombok-generated `isEnabled()` now reflects the
  `enabled` flag). `url`/`api-key` documented as the optional default
  instance.
- `application.properties`: OCG block rewritten (adds `OCG_ENABLED`,
  drops `OCG_MODEL`, documents SDK-side declaration).

**Start-time fail-fast:**
- `OcgToolValidator` — rejects agent starts whose OCG tools have no bound
  `url` (there is no server-side default instance), and any OCG tool when
  `agentspan.ocg.enabled=false`. Walks sub-agents and inline `agent_tool`
  children (both typed `AgentConfig` and raw SDK-serialized maps). Wired
  into `AgentService.start()`.

### SDK — Stage 2 (complete)

- New module `sdk/python/src/agentspan/agents/ocg.py`:
  - `OCG_SYSTEM_PROMPT` — moved verbatim from `OcgAgentFactory`, including
    the execution-time `${workflow.input.__today__}` date anchor and the
    open-range `end_time` guidance. The SDK is now the only home of the
    prompt and schemas.
  - `ocg_tools(url=, credential=, query=, entities=, code_history=, memory=)`
    — the seven raw `ToolDef`s with the canonical schemas; instance binding
    lands in each tool's `config` and declares the credential name for
    execution-token bounding. `credential` without `url` raises
    `ValueError`.
  - `ocg_agent(model=, name=, url=, credential=, instructions=, max_turns=, …)`
    — prebuilt retrieval `Agent`; `model` is keyword-required (no silent
    default). Docstrings carry the multi-instance distinct-name warning.
  - Exported from `agentspan.agents`.

### Validation — Stage 3 (complete)

Per root `CLAUDE.md`, every new behavior test was written first, run, and
its failure observed before the implementation landed (e.g. the
ToolCompiler instance-config and selfDescribing tests failed 2/16; the
`OcgRequestTask` per-instance tests failed 6/8; the validator tests failed
4/7 against a stub) — then turned green.

- Server (`conductor-agentspan-server` suite — **all green**, incl. the
  full pre-existing suite):
  - `OcgRequestTaskTest` (8) — URL override / fallback / no-instance
    failure message, pre-resolved + placeholder + unresolvable auth,
    default api-key, reserved-input stripping from request bodies.
  - `OcgToolValidatorTest` (7) — per-tool url, default fallback, rejection
    messages, feature-disabled, inline-child map walk, sub-agent walk.
  - `ToolCompilerTest` (+3) — every spec `selfDescribing`, ocgConfig
    carries url + `Bearer #{NAME}` (standalone escaping), default-instance
    entry stays minimal.
  - `AgentCompilerTest` (+1) — inverse of the deleted merger tests:
    compile never injects undeclared tools.
- SDK: `tests/unit/test_ocg.py` (16, **all green**) — subset switches,
  instance binding in config + declared credentials, `credential`-without-
  `url` rejection, schema required-fields, prompt anchor survival, exports,
  and an end-to-end serialization test proving the wire shape
  (`toolType: ocg_query`, `config.url/credential/credentials`) that the
  server-side `ToolCompiler` consumes.
- Note: the full SDK unit suite has ~138 pre-existing failures under the
  system Python — identical count on a clean tree; unrelated to this
  change. Under the `uv` env, adjacent suites pass. `ruff format` +
  `ruff check` clean on all new SDK files.
- **e2e (`sdk/python/e2e/test_suite22_ocg.py`) — 3/3 green** against a
  live server running this build: two stub OCG instances; the US-bound
  retriever's `OCG_QUERY` traffic landed only on the US stub, the
  Canada-bound only on the Canada stub, and the no-OCG agent produced zero
  OCG traffic. Validation is recorded HTTP traffic, never LLM-judged.
- The e2e caught and fixed a real pre-existing bug: `OcgRegisteredTaskDefs`
  registered TaskDefs under the operation labels (`query`, `memory_set`, …)
  while dispatch schedules tasks named `taskType.toLowerCase()`
  (`ocg_query`, …) — every OCG dispatch failed with "Cannot find task by
  name ocg_query". Fixed (names now derive from `TASK_TYPE`), covered by
  `OcgRegisteredTaskDefsTest` (fail-first), verified live.

### Documentation — Stage 4 (complete)

- `docs/ocg-agent-flow.md` rewritten for the SDK-declared design: usage,
  multi-instance binding, custom retrieval agents, server setup
  (`enabled`/default instance, no `OCG_MODEL`), execution flow incl.
  credential handling, the operations table, and a migration-from-
  auto-expose section.
- `docs/python-sdk/api-reference.md` — new "ocg_agent() / ocg_tools()"
  section under Tools (parameters table, multi-instance example, custom
  agent example, non-SDK JSON note).
- `docs/release-notes/2026-06-12-ocg-sdk-subagent.md` — breaking changes,
  new capabilities, the TaskDef-naming fix, and a migration table.

### Empirical finding worth knowing (cross-repo)

On the live server, the *top-level* `selfDescribing` key is dropped when
the spec deserializes through `org.conductoross.conductor.ai.models.ToolSpec`
(no such field; unknown keys ignored) — but `configParams.selfDescribing`
survives intact in the bound LLM task input (verified on a live
execution). That made `configParams` the chosen transport: **no
conductor-oss change or release is needed**; `OrkesLLM` reads the marker
from `getConfigParams()`.

---

## Pending

1. **orkes-conductor `selfDescribing` consumer — planned, unblocked.**
   No conductor-oss work required (the marker rides `configParams`; see
   the empirical finding above). Paste-ready plan at
   [`2026-06-12-toolspec-selfdescribing-plan.md`](2026-06-12-toolspec-selfdescribing-plan.md):
   `OrkesLLM` reads `configParams.selfDescribing` and the heuristic is
   removed outright (no fallback, per 2026-06-12 decision), plus the
   `application.properties` OCG block update. Lands on PR #3673's branch
   whenever ready. A first-class `ToolSpec` field remains optional future
   cleanup, nothing depends on it.
2. **TypeScript SDK parity** — explicitly out of scope in the design;
   same wire format when picked up.
3. **Commit the work** — everything above is uncommitted on
   `feature/OCG_System_Task`. The deployed local server at
   `~/.agentspan/server/agentspan-runtime.jar` runs this build (previous
   jar preserved as `agentspan-runtime.jar.bak`).

---

## Addendum (2026-06-12, later): server-side default instance removed

Per user decision, the optional server-wide default OCG instance
(`agentspan.ocg.url` / `api-key`) is gone entirely. `url=` is now required
on `ocg_agent()`/`ocg_tools()`; `OcgProperties` is just
`enabled` + `responseCapChars`; `OcgRequestTask`/`OcgToolValidator` have no
fallback. SDK, examples, smoke, and docs updated; full server suite + SDK
unit tests green; republished to mavenLocal.

## Addendum (2026-06-12, latest): OCG execution layer deleted — plain HttpTask

Per user decision, the custom OCG system tasks are gone entirely. The
`runtime/ocg` package (request task, operations, target, validator,
credential resolver, properties, task-def registration) is deleted; the
seven `ocg_*`→`OCG_*` TYPE_MAP entries and all `agentspan.ocg.*`
properties with it. In exchange the generic `http` enrich path gained
optional `pathTemplate`/`queryParams` (URL-encoded fill from LLM args,
consumed args pruned from the body) — proven by GraalJS execution tests.
The SDK's `ocg_tools()` now emits `tool_type="http"` defs carrying
method/pathTemplate/queryParams/headers per operation; auth uses the
standard http-tool `${NAME}` header placeholder. Rationale: the OCG API
already returns LLM-friendly responses, removing the last justification
(projection/capping) for server-side OCG code. Full server suite + SDK
tests green; two-stub e2e 3/3 on the HttpTask path; republished to
mavenLocal.
