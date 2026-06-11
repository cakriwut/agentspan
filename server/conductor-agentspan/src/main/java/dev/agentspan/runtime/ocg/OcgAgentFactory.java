/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Builds the {@link AgentConfig} for the server-registered OCG sub-agent.
 *
 * <p>The agent itself is a vanilla LLM-driven agent: it has instructions
 * (the OCG retrieval prompt) and seven {@code ocg_*} tools that dispatch to
 * the OCG_* system tasks registered by {@link OcgRequestTaskConfig}.</p>
 */
public final class OcgAgentFactory {

    /** Stable workflow name for the registered OCG sub-agent. */
    public static final String AGENT_NAME = "_ocg_agent";

    /**
     * Tool name as the main agent's LLM sees it. Distinct from {@link #AGENT_NAME}
     * because workflows registered server-side use the underscore-prefix
     * convention while LLM-visible tool names don't.
     */
    public static final String TOOL_NAME = "ocg_agent";

    /**
     * Description shown to the main agent's LLM in its tool spec list. Kept
     * concrete enough that the model can decide <em>when</em> to delegate
     * without leaking implementation details into the user-facing prompt.
     */
    public static final String TOOL_DESCRIPTION =
            "Delegate to the OCG (Open Context Graph) retrieval agent when you need "
                    + "context from the knowledge graph — message search, entity lookup, "
                    + "code history, or stored memories. Provide a focused natural-language "
                    + "query (under ~15 content words). Returns a synthesized answer with "
                    + "supporting citations.";

    /**
     * Conductor expression the system prompt uses as its date anchor.
     * Resolved per execution against the {@code _ocg_agent} sub-workflow's
     * input — the agent_tool dispatch script injects {@code __today__} with
     * the current UTC date on every call. Anchoring at execution time (not
     * boot time) is what keeps "recent" / relative-date queries correct on
     * a long-running server; a date baked in at registration would drift
     * until the prompt claimed last month was "today".
     */
    static final String TODAY_EXPRESSION = "${workflow.input.__today__}";

    /**
     * System prompt for the OCG sub-agent. {@link #TODAY_EXPRESSION} is
     * substituted by Conductor when the LLM task is scheduled, so any
     * "recent" / relative-date query gets bounded against the real current
     * date instead of whatever year the model felt like inventing.
     */
    static final String OCG_SYSTEM_PROMPT = "Today's date is " + TODAY_EXPRESSION + " (UTC). When a user asks for\n"
            + "\"recent\" / \"last week\" / any relative range, anchor on this date.\n"
            + "Never invent a date range — if no range is implied by the user, omit\n"
            + "start_time/end_time from the request.\n"
            + "If the range extends to the present (\"recent\", \"current state\",\n"
            + "\"catch me up\", \"last N days\"), set start_time and OMIT end_time —\n"
            + "the results then run through now. Only set end_time when the user\n"
            + "asks about a window that closed in the past. Never end a range at a\n"
            + "month boundary before today; that silently drops the newest data.\n\n"
            + "You are querying an OCG (Observability Context Graph). It is a RETRIEVAL\n"
            + "engine over a knowledge graph of entities (messages, channels, people)\n"
            + "linked by claims and relationships. It is NOT an aggregation engine.\n\n"
            + "It can answer:\n"
            + "  - \"Find messages in channel X about Y\"\n"
            + "  - \"Show TIMED_OUT errors for cluster <name>\"\n"
            + "  - \"What entities mention 'health check failure'?\"\n"
            + "  - \"Recent messages in #cloud_saas_health_check_alerts\"\n\n"
            + "It CANNOT directly answer (you must do it yourself in two steps):\n"
            + "  - \"How many of X are there?\" / \"Which X is most frequent?\"\n"
            + "  - \"Group these by Y\" / \"Top N by count\"\n"
            + "  - Statistical or comparative questions\n\n"
            + "For aggregation questions, use a TWO-STEP pattern:\n"
            + "  1. RETRIEVE: ask OCG for the raw set of relevant entities.\n"
            + "     - Use specific terms (cluster names, error codes, channel names).\n"
            + "     - Use start_time (and end_time only for windows closed in the\n"
            + "       past) to bound the range.\n"
            + "     - Set max_results high (e.g. 500) so you get the full set, not a\n"
            + "       top-N sample.\n"
            + "     - Avoid hedging words (\"frequently\", \"across\", \"occurrences\") —\n"
            + "       OCG ranks by keyword presence, and these are noise tokens.\n"
            + "  2. AGGREGATE: count, group, rank yourself from the citation list.\n\n"
            + "Query length: keep it under ~15 content words. Long prompts dilute the\n"
            + "BM25 keyword set; OCG's parser is extracting things like \"happen\",\n"
            + "\"identify\", \"top one\" which are not real signal.\n\n"
            + "Bad:  \"Across all clusters, what alert/notification/error type appears\n"
            + "       most frequently? Group similar alerts and tell me which one has\n"
            + "       the highest count and how many clusters it affected.\"\n\n"
            + "Good (step 1): {\n"
            + "  \"query\": \"TIMED_OUT health check failure cluster\",\n"
            + "  \"max_results\": 500,\n"
            + "  \"start_time\": \"<today minus 30 days>T00:00:00Z\"\n"
            + "}\n"
            + "(end_time omitted — the range runs through now.)\n"
            + "Then parse the returned citations, extract cluster names from titles,\n"
            + "build the frequency table in your reasoning.";

    private OcgAgentFactory() {}

    public static AgentConfig build(OcgProperties props) {
        if (StringUtils.isBlank(props.getModel())) {
            throw new IllegalArgumentException(
                    "OCG is enabled (agentspan.ocg.url is set) but agentspan.ocg.model is blank. "
                            + "Set OCG_MODEL (or -Dagentspan.ocg.model=…) to the LLM the OCG sub-agent "
                            + "should use, e.g. OCG_MODEL=openai/gpt-4o-mini.");
        }
        return AgentConfig.builder()
                .name(AGENT_NAME)
                .description("Retrieval sub-agent over the Open Context Graph (OCG).")
                .model(props.getModel())
                .instructions(OCG_SYSTEM_PROMPT)
                .tools(buildTools())
                .maxTurns(10)
                .build();
    }

    static List<ToolConfig> buildTools() {
        return List.of(
                queryTool(),
                getEntityTool(),
                neighborhoodTool(),
                codeHistoryTool(),
                memorySetTool(),
                memoryReinforceTool(),
                memoryDeleteTool());
    }

    private static ToolConfig queryTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", schema("string", "Natural-language retrieval query."));
        properties.put("max_results", schema("integer", "Max citations to return.", 10));
        properties.put(
                "traversal_level", schema("integer", "0 = citations only, 1 = neighborhood, 2-3 = multi-hop.", 1));
        properties.put("start_time", schema("string", "ISO-8601 lower bound (inclusive). Optional."));
        properties.put("end_time", schema("string", "ISO-8601 upper bound (exclusive). Optional."));
        return ToolConfig.builder()
                .name("ocg_query")
                .toolType("ocg_query")
                .description("Query the Open Context Graph for structured retrieval. "
                        + "Returns citations (source_item_id, title, container_id, snippet) "
                        + "and traversal_results when traversal_level > 0.")
                .inputSchema(objectSchema(properties, List.of("query")))
                .build();
    }

    private static ToolConfig getEntityTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_id", schema("string", "Canonical entity id from an ocg_query result row."));
        return ToolConfig.builder()
                .name("ocg_get_entity")
                .toolType("ocg_get_entity")
                .description("Fetch one entity by its canonical id.")
                .inputSchema(objectSchema(properties, List.of("entity_id")))
                .build();
    }

    private static ToolConfig neighborhoodTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_id", schema("string", "Entity at the center of the neighborhood."));
        properties.put("depth", schema("integer", "Hop depth (use depth=1 on first call).", 2));
        properties.put("limit", schema("integer", "Cap on neighbors returned (use <= 10 on first call).", 50));
        return ToolConfig.builder()
                .name("ocg_neighborhood")
                .toolType("ocg_neighborhood")
                .description("Get an entity plus its graph neighbors out to `depth` hops. "
                        + "Use limit <= 10, depth=1 on the first call — well-connected "
                        + "entities can have many edges and large responses will be truncated.")
                .inputSchema(objectSchema(properties, List.of("entity_id")))
                .build();
    }

    private static ToolConfig codeHistoryTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repo_id", schema("string", "Ingested repository id."));
        properties.put("path", schema("string", "Path within the repo."));
        properties.put("limit", schema("integer", "Max commits to return.", 20));
        return ToolConfig.builder()
                .name("ocg_code_history")
                .toolType("ocg_code_history")
                .description("Last N commits that touched a file in an ingested repo.")
                .inputSchema(objectSchema(properties, List.of("repo_id", "path")))
                .build();
    }

    private static ToolConfig memorySetTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("key", schema("string", "Memory key."));
        properties.put("agent", schema("string", "Agent owner (e.g. \"agent:<name>\")."));
        properties.put("user", schema("string", "User owner (e.g. \"user:<name>\")."));
        properties.put("string_value", schema("string", "Stored value."));
        properties.put("description", schema("string", "Human-readable description."));
        properties.put(
                "scope",
                schema(
                        "string",
                        "Memory scope. One of MEMORY_SCOPE_SESSION, MEMORY_SCOPE_AGENT, MEMORY_SCOPE_USER, MEMORY_SCOPE_SHARED, MEMORY_SCOPE_GLOBAL.",
                        "MEMORY_SCOPE_USER"));
        properties.put("confidence", schema("number", "Inferred confidence in [0,1]. Cap at 0.7.", 0.7));
        properties.put("source_ref", schema("string", "Free-form source reference (e.g. message id)."));
        properties.put("evidence_ids", arraySchema("string", "Supporting evidence entity ids."));
        properties.put("tags", arraySchema("string", "Tags."));
        properties.put("expires_at", schema("string", "ISO-8601 expiry. Optional — default 180 days."));
        properties.put("idempotency_key", schema("string", "Idempotency key. Optional."));
        return ToolConfig.builder()
                .name("ocg_memory_set")
                .toolType("ocg_memory_set")
                .description("Create or overwrite a memory in OCG. Cap inferred confidence at 0.7; "
                        + "never write PII or secrets.")
                .inputSchema(objectSchema(properties, List.of("key", "agent", "user", "string_value", "description")))
                .build();
    }

    private static ToolConfig memoryReinforceTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("key", schema("string", "Memory key."));
        properties.put("agent", schema("string", "Agent owner."));
        properties.put("user", schema("string", "User owner."));
        properties.put(
                "confidence_boost",
                schema("number", "Boost to add (must be <= 0.05 to prevent compounding drift).", 0.05));
        properties.put("source_ref", schema("string", "Free-form source reference."));
        return ToolConfig.builder()
                .name("ocg_memory_reinforce")
                .toolType("ocg_memory_reinforce")
                .description("Reinforce an existing memory on independent re-observation. "
                        + "confidence_boost must be <= 0.05.")
                .inputSchema(objectSchema(properties, List.of("key", "agent", "user")))
                .build();
    }

    private static ToolConfig memoryDeleteTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("key", schema("string", "Memory key."));
        properties.put("agent", schema("string", "Agent owner."));
        properties.put("user", schema("string", "User owner."));
        return ToolConfig.builder()
                .name("ocg_memory_delete")
                .toolType("ocg_memory_delete")
                .description("Delete a memory by key. Prefer ocg_memory_set with a corrected value "
                        + "over deletion (preserves history).")
                .inputSchema(objectSchema(properties, List.of("key", "agent", "user")))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  JSON-schema helpers
    // ─────────────────────────────────────────────────────────────────────

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> schema(String type, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("description", description);
        return s;
    }

    private static Map<String, Object> schema(String type, String description, Object defaultValue) {
        Map<String, Object> s = schema(type, description);
        s.put("default", defaultValue);
        return s;
    }

    private static Map<String, Object> arraySchema(String itemType, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "array");
        s.put("description", description);
        s.put("items", Map.of("type", itemType));
        return s;
    }
}
