# OCG Sub-Agent

A retrieval sub-agent over the Open Context Graph (OCG) — message search,
entity lookup, code history, stored memories — that any agent **opts into
from the SDK**. The SDK is the canonical — and only — home of the OCG
integration: system prompt, tool schemas, endpoint routing, and instance
binding. The tools compile to **plain Conductor HTTP tasks** (with path
templating); there is no OCG-specific server code at all. The OCG API
itself returns LLM-friendly responses.

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

**None.** OCG tools are plain HTTP tools: no properties, no task types, no
TaskDefs, no beans. Any agentspan server (standalone or embedded) that runs
HTTP tools runs OCG tools. Nothing is registered at boot — retrieval agents
are compiled when a user agent that declares them starts.

---

## How a call executes

```
main agent LLM ── tool call ──▶ SUB_WORKFLOW (retriever)
                                   │  retriever LLM picks e.g. ocg_get_entity
                                   ▼
                            enrich script (compile-time JS, dispatch-time eval):
                              uri  = <instance-url> + pathTemplate filled from
                                     the LLM's args (URL-encoded) + queryParams
                              body = remaining args (consumed args removed)
                              headers carry the credential placeholder
                                   ▼
                            standard Conductor HTTP task
                                   ▼
                            OCG instance  ─── citations ──▶ retriever LLM
```

Key properties:

- **Per-call instance binding.** Each tool's config carries its instance
  `url` (required by the SDK) — different agents target different graphs.
- **Secrets stay server-side.** `credential="OCG_US_KEY"` compiles to a
  standard HTTP-tool header placeholder (`#{OCG_US_KEY}` standalone,
  `${workflow.secrets.OCG_US_KEY}` embedded), resolved from the credential
  store at execution — the same pipeline every `http_tool` uses.
- **LLM-friendly responses are the OCG API's job.** The API returns compact
  citation-shaped responses; agentspan applies no OCG-specific projection
  or capping.

## The seven OCG operations

Endpoint routing lives in the SDK (`agentspan/agents/ocg.py`) and compiles
into each tool's HTTP config (`pathTemplate` + `queryParams` + method):

| Tool name (LLM-visible) | Endpoint                                 | Method   |
| ----------------------- | ---------------------------------------- | -------- |
| `ocg_query`             | `/api/v1/agent/query`                    | `POST`   |
| `ocg_get_entity`        | `/api/v1/entities/{entity_id}`           | `GET`    |
| `ocg_neighborhood`      | `/api/v1/graph/neighborhood/{entity_id}` | `GET`    |
| `ocg_code_history`      | `/api/v1/code/history/{repo_id}`         | `GET`    |
| `ocg_memory_set`        | `/api/v1/memories`                       | `POST`   |
| `ocg_memory_reinforce`  | `/api/v1/memories/{key}/reinforce`       | `POST`   |
| `ocg_memory_delete`     | `/api/v1/memories/{key}`                 | `DELETE` |

Path params (`{entity_id}`, `{key}`, `{repo_id}`) are filled from the
LLM's tool arguments and URL-encoded by the dispatch script; listed query
params are appended when present; everything else becomes the JSON body.

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
| Seven `OCG_*` system tasks + TaskDefs + `agentspan.ocg.*` properties | Plain HTTP tasks — zero OCG server code; `agentspan.ocg.*` properties are gone and ignored |

Anything that referenced the `_ocg_agent` workflow by name breaks; declare
the agent from the SDK instead.

## Non-SDK clients

REST/UI clients inline the equivalent agent JSON: an `agent_tool` whose
`config.agentConfig` carries the retrieval agent — `tools` entries with
`toolType: "ocg_query"` … `"ocg_memory_delete"`, each optionally with
`config: {"url": ..., "credential": ...}`. The canonical prompt is exported
as `agentspan.agents.ocg.OCG_SYSTEM_PROMPT`.
