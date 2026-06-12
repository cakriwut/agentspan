"""Jira-over-OCG smoke check.

OCG is now opt-in from the SDK (auto-expose is gone), so the agent must
declare its retrieval tooling explicitly. This smoke uses the sub-agent
shape; both shapes live as SDK examples:

  - sdk/python/examples/116_ocg_subagent.py     (delegate to ocg_agent())
  - sdk/python/examples/117_ocg_direct_tools.py (main agent calls ocg_query)

Run (from sdk/python, against the embedded orkes server on 8080)::

    OCG_URL=https://test.contextgraph.io \
    OCG_CREDENTIAL=OCG_PUBLIC_KEY \
    uv run python ../../e2e/ocg/jira_ocg_smoke.py

OCG_CREDENTIAL names a secret in the server's secrets store holding the
instance's bearer token (store it once, e.g. orkes UI -> Secrets, or
PUT /api/secrets/OCG_PUBLIC_KEY). The token itself never appears here.
"""

import os

from agentspan.agents import Agent, AgentRuntime, agent_tool
from agentspan.agents.ocg import ocg_agent

# The agentspan runtime is embedded in the Conductor server, which listens
# on 8080 (not the standalone default 6767). Override via env if needed.
SERVER_URL = os.environ.get("AGENTSPAN_SERVER_URL", "http://localhost:8080/api")
MODEL = os.environ.get("AGENTSPAN_LLM_MODEL", "openai/gpt-4o-mini")

# Every OCG tool binds the instance it talks to — there is no server-side
# default. OCG_CREDENTIAL optionally names a credential-store entry for the
# instance's bearer token (for unauthenticated/local instances, leave unset).
OCG_URL = os.environ.get("OCG_URL") or ""
OCG_CREDENTIAL = os.environ.get("OCG_CREDENTIAL")
if not OCG_URL:
    raise SystemExit("Set OCG_URL to your OCG instance, e.g. https://test.contextgraph.io")

prompt = (
    "Catch me up on 'Improvements to Python SDK -- performance, Feature "
    "parity, logging, metrics etc'. What's the current state, what's "
    "underneath it, and what's been changing in the codebase?"
)

retriever = ocg_agent(name="ocg_retriever", model=MODEL, url=OCG_URL, credential=OCG_CREDENTIAL)

agent = Agent(
    name="jira_ocg_smoke",
    model=MODEL,
    instructions=(
        "You answer questions about the team's work. Delegate every lookup "
        "to your retrieval tool and synthesize its cited answer."
    ),
    tools=[agent_tool(retriever)],
)

if __name__ == "__main__":
    with AgentRuntime(server_url=SERVER_URL) as runtime:
        result = runtime.run(agent, prompt)
        result.print_result()
