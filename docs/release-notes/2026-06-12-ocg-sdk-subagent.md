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

4. **All OCG server code removed.** OCG tools compile to plain Conductor
   HTTP tasks (with path templating, a new generally-available `http`
   tool capability). The seven `OCG_*` system task types, their TaskDefs,
   and every `agentspan.ocg.*` property (`enabled`/`url`/`api-key`/
   `model`/`response-cap-chars`) are gone and ignored. Every OCG tool
   binds its own instance (`url=`, required) from the SDK; the OCG API
   itself returns LLM-friendly responses (no server-side projection or
   capping).

## New

- `agentspan.agents.ocg` — `ocg_agent()` (prebuilt retrieval agent),
  `ocg_tools()` (raw tool defs, subset switches), `OCG_SYSTEM_PROMPT`.
- Per-tool instance binding: `ocg_agent(url=..., credential=...)` — `url`
  is required; the credential is a credential-store *name*, resolved
  server-side at execution; secrets never enter Python code or workflow
  definitions.
- HTTP tool path templating: `http`-type tool configs may declare
  `pathTemplate` (e.g. `/api/v1/entities/{entity_id}`) and `queryParams`;
  the dispatch script fills them from the LLM's arguments (URL-encoded)
  and prunes consumed args from the body. OCG uses this; any HTTP tool
  can.
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
