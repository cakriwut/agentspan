# Plan: `selfDescribing` Tool-Spec Marker — orkes-conductor only

**Date:** 2026-06-12 (revised same day: no conductor-oss dependency)
**Status:** Ready — agentspan's producer side is merged and verified live
**Parent design:** [2026-06-12-ocg-sdk-subagent-design.md](2026-06-12-ocg-sdk-subagent-design.md) §5

## Context

AgentSpan compiles **self-describing** tool specs — name, description, and
`inputSchema` ship inline in the `LLM_CHAT_COMPLETE` task input. Orkes'
`OrkesLLM` worker resolves tools **by name against the integration store**,
so AgentSpan specs were dropped until
[orkes-conductor PR #3673](https://github.com/orkes-io/orkes-conductor/pull/3673)
added a heuristic: `inputSchema != null` ⇒ pass the spec through.

The durable contract is an explicit marker meaning: *this spec is complete
as delivered — hand it to the LLM as-is; do not resolve, enrich, or replace
it by name against integrations, services, or task definitions.*

## Transport: `configParams`, not a new field

A first-class `ToolSpec.selfDescribing` field would require a conductor-oss
change + release + `revConductor` bump (conductor-ai is a Maven dependency
of orkes-conductor, not vendored). **Decision (2026-06-12): don't wait for
that.** `ToolSpec` already has `configParams: Map<String, Object>` — a
generic map that survives deserialization — so the marker rides there.

AgentSpan (already merged + verified) stamps every compiled spec with both:

```json
{
  "name": "ocg_query",
  "type": "OCG_QUERY",
  "selfDescribing": true,                      // future-proofing only — dropped today
  "configParams": {"selfDescribing": true},    // the copy that survives
  ...
}
```

**Verified live:** in a bound `LLM_CHAT_COMPLETE` task input on a running
server, the top-level key is stripped at `ToolSpec` deserialization
(`ObjectMapperProvider` drops unknown properties) while
`configParams.selfDescribing == true` arrives intact. For MCP/API tools the
marker is merged into their existing `configParams` (mcpServer/baseUrl
entries untouched).

The top-level key stays in the compiled spec so that if a first-class field
ever lands upstream, consumers can switch to `isSelfDescribing()` with no
producer change — but nothing depends on it.

---

## The orkes-conductor change (unblocked, single repo)

Branch: the agentspan-embed work already lives on
`feature/agentspan-embed-spike-nich-changes` (PR #3673) — push there, or
stack a follow-up PR if #3673 merges first.

### Change 1 — `workers/src/main/java/io/orkes/conductor/enterprise/workers/integrations/OrkesLLM.java`

Replace the PR #3673 heuristic at the top of `getToolSpecs(...)` — the
`inputSchema != null` check is removed entirely (no fallback kept):

```java
    private List<ToolSpec> getToolSpecs(String orgId, ToolSpec toolSpec) {
        if (isSelfDescribing(toolSpec)) {
            // Self-describing spec (e.g. compiled by AgentSpan): complete
            // as delivered — hand it to the LLM as-is, never resolve by
            // name against integrations/services/taskdefs.
            return List.of(toolSpec);
        }
        // existing name-based integration resolution below, unchanged
        ...
    }

    /**
     * The marker rides configParams because ToolSpec has no dedicated
     * field; a generic map entry survives deserialization where an
     * unknown top-level key would be dropped.
     */
    private static boolean isSelfDescribing(ToolSpec toolSpec) {
        Map<String, Object> cp = toolSpec.getConfigParams();
        return cp != null && Boolean.TRUE.equals(cp.get("selfDescribing"));
    }
```

Defined name-collision semantics: a self-describing spec whose name matches
a registered integration passes through (inline wins, by declared intent).

Note: markerless specs fall through to name resolution and are dropped —
that only affects executions already mid-flight at upgrade time, since
agent workflows re-register on every start and any new start carries the
marker.

### Change 2 — `server/src/main/resources/application.properties`

Replace the OCG block added in PR #3673 (it documents auto-expose and
requires `OCG_MODEL`, both removed from agentspan). DONE in the local
checkout — the block is now just the enable switch (every OCG tool binds
its own instance from the SDK; there is no server-side instance config):

```properties
# =============================================================================
# OCG (Open Context Graph) Configuration
# =============================================================================
# OCG agents and tools are declared in user code via the AgentSpan SDK
# (ocg_agent() / ocg_tools()), each binding its own OCG instance URL and
# credential-store reference. There is no server-side OCG configuration
# beyond this switch, which registers the OCG_* system tasks.
agentspan.ocg.enabled=${OCG_ENABLED:false}
```

(`agentspan.ocg.model`/`url`/`api-key` are removed and ignored by
agentspan. The host defaults the switch to false — set `OCG_ENABLED=true`
in the runtime environment to turn the execution layer on.)

### Tests (fail-first)

`OrkesLLM.getToolSpecs()` unit tests:
- spec with `configParams.selfDescribing == true` → passed through;
  integration lookup **never invoked** (verify on the mocked integration
  service);
- spec without the marker (with or without schema, with or without other
  configParams entries) → existing name-resolution path;
- marked spec whose name matches a registered integration → inline wins;
- MCP-shaped spec (`configParams` carrying `mcpServer` **and** the marker)
  → passed through with configParams intact.

After this lands, schema presence means nothing to `OrkesLLM`; the
configParams marker is the sole contract.

---

## Optional future cleanup (conductor-oss, no urgency)

If/when convenient, add `private boolean selfDescribing` to
`ai/src/main/java/org/conductoross/conductor/ai/models/ToolSpec.java`
(Javadoc: complete-as-delivered semantics, primitive boolean for
backward-compatible absence). AgentSpan already emits the top-level key, so
on a dependency bump `OrkesLLM` can simplify to
`toolSpec.isSelfDescribing() || configParamsMarker(toolSpec)` and the
configParams transport can be retired one release later. None of the above
waits on this.
