# Release Note — OCG Becomes an SDK-Declared Sub-Agent (Breaking)

**Date:** 2026-06-12

OCG (Open Context Graph) retrieval is now **declared in user code via the
Python SDK** and supports **per-agent instance binding** (multi-tenancy /
data residency). The server keeps only the execution layer.

## Breaking changes

1. **Auto-expose is removed.** Setting `OCG_URL` no longer injects an
   `ocg_agent` tool into every agent. Agents that need retrieval must opt
   in:

   ```python
   from agentspan.agents import agent_tool
   from agentspan.agents.ocg import ocg_agent

   tools=[agent_tool(ocg_agent(model="openai/gpt-4o-mini"))]
   ```

2. **The `_ocg_agent` workflow is no longer registered at boot.** Anything
   referencing it by name breaks; retrieval agents compile when the
   declaring agent starts.

3. **`OCG_MODEL` / `agentspan.ocg.model` is removed and ignored.** The
   retrieval agent's model is a required SDK parameter. The boot-time
   fail-fast tied to it is gone.

4. **Gating changed; server-side instance config removed.** The `OCG_*`
   system tasks are registered when `agentspan.ocg.enabled=true` (the
   default) — no longer conditional on `OCG_URL`. `agentspan.ocg.url` /
   `api-key` are **gone**: every OCG tool binds its own instance
   (`url=`, required) from the SDK; there is no server-wide default.

## New

- `agentspan.agents.ocg` — `ocg_agent()` (prebuilt retrieval agent),
  `ocg_tools()` (raw tool defs, subset switches), `OCG_SYSTEM_PROMPT`.
- Per-tool instance binding: `ocg_agent(url=..., credential=...)` — `url`
  is required; the credential is a credential-store *name*, resolved
  server-side at execution; secrets never enter Python code or workflow
  definitions.
- Start-time validation: an OCG tool without a bound `url` is rejected at
  agent start (not mid-conversation), as is any OCG tool when
  `agentspan.ocg.enabled=false`.
- Every compiled tool spec now carries a `selfDescribing: true` marker —
  top-level and inside `configParams` (the copy that survives `ToolSpec`
  deserialization) — consumed by embedding hosts (orkes-conductor's
  `OrkesLLM`) to pass AgentSpan tool specs to the LLM without
  integration-store resolution.

## Fixed

- OCG TaskDefs are now registered under the names the dispatch script
  actually schedules (`ocg_query`, …, `ocg_memory_delete`). Previously they
  were registered under the operation labels (`query`, `memory_set`, …),
  which failed dynamic-fork dispatch with *"Cannot find task by name
  ocg_query in the task definitions."*

## Migration

| If you relied on… | Do this instead |
| --- | --- |
| Auto-injected `ocg_agent` on every agent | Add `agent_tool(ocg_agent(model=...))` to each agent that needs retrieval |
| `OCG_MODEL` env var | Pass `model=` to `ocg_agent()` |
| The boot-registered `_ocg_agent` workflow | Declare the retriever from the SDK |
| Server-wide `OCG_URL`/`OCG_API_KEY` | Bind per tool: `ocg_agent(url=..., credential=...)` |

Details: `docs/ocg-agent-flow.md` and
`docs/design/2026-06-12-ocg-sdk-subagent-design.md`.
