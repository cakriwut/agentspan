# OCG Sub-Agent

A retrieval sub-agent over the Open Context Graph (OCG) — message search,
entity lookup, code history, stored memories — that any agent **opts into
from the SDK**. The SDK is the canonical home of the OCG agent (system
prompt, tool schemas, instance binding); the server provides only the
execution layer: seven `OCG_*` system tasks that make the authenticated
HTTP calls, resolve credentials, project response fields, and cap response
sizes.

Nothing is auto-injected: an agent that doesn't declare OCG tools never
makes an OCG call. (The previous design — a server-registered `_ocg_agent`
silently appended to every agent when `OCG_URL` was set — is gone; see
[Migration](#migration-from-auto-expose).)

Design history: [`design/2026-06-12-ocg-sdk-subagent-design.md`](design/2026-06-12-ocg-sdk-subagent-design.md).

---

## Using OCG from the SDK

Delegate retrieval from a main agent:

```python
from agentspan.agents import Agent, agent_tool
from agentspan.agents.ocg import ocg_agent

retriever = ocg_agent(model="openai/gpt-4o-mini",
                      url="https://ocg.example.com", credential="OCG_KEY")
main = Agent(
    name="support",
    model="openai/gpt-4o",
    tools=[agent_tool(retriever)],
    instructions="...",
)
```

The main agent's LLM sees one tool (named after the retriever); calling it
dispatches the retrieval agent as a SUB_WORKFLOW, which runs its own LLM
loop with the seven `ocg_*` tools and returns a synthesized, cited answer.

### Multi-instance (data residency / multi-tenancy)

Each retriever binds its own OCG instance — different agents can target
different graphs:

```python
us = ocg_agent(name="ocg_us", model="openai/gpt-4o-mini",
               url="https://us.ocg.example.com", credential="OCG_US_KEY")
ca = ocg_agent(name="ocg_canada", model="openai/gpt-4o-mini",
               url="https://ca.ocg.example.com", credential="OCG_CA_KEY")
```

- `url` — the OCG instance this retriever (and only this retriever) talks
  to. Required: every OCG tool binds its own instance; there is no
  server-side default.
- `credential` — the **name** of a credential-store entry holding the OCG
  bearer token. The server resolves it at execution time; the secret never
  appears in Python code, serialized configs, or persisted workflow
  definitions. Requires `url`.
- **Names must be distinct per instance.** Inline `agent_tool` child
  workflows are registered by agent name — two differently-bound agents
  sharing a name overwrite each other's workflow definition.

### Custom retrieval agents

`ocg_agent()` is just an `Agent` factory. For full control take the raw
tools and build your own:

```python
from agentspan.agents.ocg import ocg_tools

my_retriever = Agent(
    name="retriever",
    model="anthropic/claude-haiku-4-5",        # your model choice
    instructions="My custom retrieval prompt...",
    tools=ocg_tools(url="https://us.ocg.example.com",
                    credential="OCG_US_KEY",
                    memory=False),              # retrieval-only subset
)
```

Subset switches: `query`, `entities` (get_entity + neighborhood),
`code_history`, `memory` (set/reinforce/delete).

### Pre-flight retrieval

"Retrieve before the main agent acts" is expressed in user space — make the
retriever the first stage of a sequential pipeline, or instruct the main
agent to call its retrieval tool first. There is no server-side pre-flight
hook.

---

## Server setup

The server side is the execution layer only. Configuration
(`application.properties` / env):

| Property | Env var | Default | What it does |
| --- | --- | --- | --- |
| `agentspan.ocg.enabled` | `OCG_ENABLED` | `true` | Registers the seven `OCG_*` system tasks and `ocg_*` TaskDefs. When `false`, agent starts that declare OCG tools are rejected with a clear error. |
| `agentspan.ocg.response-cap-chars` | — | 8192 | Post-projection truncation cap per response. |

That's the whole server surface. There is no server-side OCG instance
configuration: no `OCG_URL`/`OCG_API_KEY` (every tool binds its own
instance from the SDK) and no `OCG_MODEL` (the retrieval agent's model is
a required SDK parameter, `ocg_agent(model=...)`).

### Verifying it's enabled

```bash
# The seven OCG TaskDefs are registered (names match dispatch):
curl -s localhost:6767/api/metadata/taskdefs \
  | jq '[.[].name | select(startswith("ocg_"))]'
# → ["ocg_query", "ocg_get_entity", ..., "ocg_memory_delete"]
```

No workflow is registered at boot — retrieval agents are compiled when a
user agent that declares them starts.

---

## How a call executes

```
main agent LLM ── tool call ──▶ SUB_WORKFLOW (retriever)
                                   │  retriever LLM picks e.g. ocg_query
                                   ▼
                            enrich script: t.type = OCG_QUERY,
                            merges __ocg_url / __ocg_auth from the
                            tool's compiled instance binding
                                   ▼
                            OcgRequestTask (system task)
                              1. target = __ocg_url (required)
                              2. auth   = __ocg_auth (placeholder resolved
                                 via credential store), absent = no auth
                              3. strip reserved inputs, build HTTP request
                              4. send → project fields → cap chars
                                   ▼
                            OCG instance  ─── citations ──▶ retriever LLM
```

Key properties:

- **Per-call instance binding.** The tool's binding (compiled into the
  workflow as the reserved `__ocg_url`/`__ocg_auth` task inputs) is the
  only instance. A tool without one is rejected at agent *start*
  (`OcgToolValidator`); the task-level check is the backstop.
- **Secrets stay server-side.** `credential="OCG_US_KEY"` compiles to a
  placeholder (`#{OCG_US_KEY}` standalone, `${workflow.secrets.OCG_US_KEY}`
  embedded). Standalone resolution goes through the credential store scoped
  by the execution token (same contract as HTTP tool headers); resolved
  values are never written back to the task model. An unresolvable
  placeholder fails the task rather than being sent as a bearer token.
- **Response hygiene.** Each operation projects the raw OCG response down
  to the fields the LLM needs (e.g. citations), then caps it at
  `response-cap-chars` *before* it is persisted or enters the LLM context.

## The seven OCG operations

All endpoints sit under `<instance-url>/api/v1`. Each is backed by a
strategy class implementing `OcgOperation` (under `runtime/ocg/operation/`);
`OcgRequestTask` is a thin orchestrator that resolves the target instance
and delegates URL/method/body/projection to the strategy.

| Tool name (LLM-visible) | System task type      | Endpoint                                 | Method   |
| ----------------------- | --------------------- | ---------------------------------------- | -------- |
| `ocg_query`             | `OCG_QUERY`           | `/api/v1/agent/query`                    | `POST`   |
| `ocg_get_entity`        | `OCG_GET_ENTITY`      | `/api/v1/entities/{entity_id}`           | `GET`    |
| `ocg_neighborhood`      | `OCG_NEIGHBORHOOD`    | `/api/v1/graph/neighborhood/{entity_id}` | `GET`    |
| `ocg_code_history`      | `OCG_CODE_HISTORY`    | `/api/v1/code/history/{repo_id}`         | `GET`    |
| `ocg_memory_set`        | `OCG_MEMORY_SET`      | `/api/v1/memories`                       | `POST`   |
| `ocg_memory_reinforce`  | `OCG_MEMORY_REINFORCE`| `/api/v1/memories/{key}/reinforce`       | `POST`   |
| `ocg_memory_delete`     | `OCG_MEMORY_DELETE`   | `/api/v1/memories/{key}`                 | `DELETE` |

The registered TaskDef names are the lowercased task types (`ocg_query`,
…) — Conductor resolves dynamically forked tasks by name, and the dispatch
script names each task `taskType.toLowerCase()`.

---

## Migration from auto-expose

Prior to 2026-06-12, setting `OCG_URL` registered a `_ocg_agent` workflow at
boot and **silently appended** an `ocg_agent` tool to every compiled agent.
That behavior is removed with no flag and no shim:

| Before | After |
| --- | --- |
| Every agent got `ocg_agent` for free when `OCG_URL` was set | Each agent opts in: `tools=[agent_tool(ocg_agent(model=...))]` |
| `_ocg_agent` workflow registered at boot | No boot-time workflow; retriever compiles when the declaring agent starts |
| `OCG_MODEL` required, boot failed fast without it | Model is a required SDK parameter; `OCG_MODEL` is ignored |
| One server-wide OCG instance | Per-tool `url=`/`credential=` — required; no server-side instance config at all |
| OCG fully off unless `OCG_URL` set | Execution layer gated by `agentspan.ocg.enabled`; instanceless OCG tools rejected at start |

Anything that referenced the `_ocg_agent` workflow by name breaks; declare
the agent from the SDK instead.

## Non-SDK clients

REST/UI clients inline the equivalent agent JSON: an `agent_tool` whose
`config.agentConfig` carries the retrieval agent — `tools` entries with
`toolType: "ocg_query"` … `"ocg_memory_delete"`, each optionally with
`config: {"url": ..., "credential": ...}`. The canonical prompt is exported
as `agentspan.agents.ocg.OCG_SYSTEM_PROMPT`.
