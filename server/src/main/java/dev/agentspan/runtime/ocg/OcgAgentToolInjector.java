/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Silently appends the OCG sub-agent as an {@code agent_tool} on the top-level
 * {@link AgentConfig} when OCG is enabled.
 *
 * <p>Extracted from {@code AgentService} as a static helper so the injection
 * rules (skip self, skip duplicates, never touch nested sub-agents) are
 * unit-testable without standing up the full service.</p>
 */
public final class OcgAgentToolInjector {

    public static final String OCG_AGENT_TOOL_NAME = "ocg_agent";

    /**
     * Description shown to the main agent's LLM in its tool spec list. Keep
     * it concrete enough that the model can decide *when* to delegate, but
     * short enough not to waste tokens.
     */
    static final String OCG_AGENT_TOOL_DESCRIPTION =
            "Delegate to the OCG (Open Context Graph) retrieval agent when you need "
                    + "context from the knowledge graph — message search, entity lookup, "
                    + "code history, or stored memories. Provide a focused natural-language "
                    + "query (under ~15 content words). Returns a synthesized answer with "
                    + "supporting citations.";

    private OcgAgentToolInjector() {}

    /**
     * Returns {@code config} with an {@code ocg_agent} tool appended if and
     * only if all of the following hold:
     * <ul>
     *   <li>{@code ocgEnabled} is true</li>
     *   <li>{@code config} is not the registered {@code _ocg_agent} itself (no self-recursion)</li>
     *   <li>{@code config} doesn't already declare a tool named {@code ocg_agent}</li>
     * </ul>
     * Otherwise returns {@code config} unchanged.
     *
     * <p>Nested sub-agents are intentionally untouched — only the top-level
     * agent receives the injection, so a specialist sub-agent isn't polluted
     * with an unrelated retrieval tool.</p>
     */
    public static AgentConfig inject(AgentConfig config, boolean ocgEnabled) {
        if (config == null || !ocgEnabled) return config;
        if (OcgAgentFactory.AGENT_NAME.equals(config.getName())) return config;

        List<ToolConfig> existing = config.getTools();
        if (existing != null) {
            for (ToolConfig t : existing) {
                if (OCG_AGENT_TOOL_NAME.equals(t.getName())) {
                    return config;
                }
            }
        }
        List<ToolConfig> merged = new ArrayList<>();
        if (existing != null) merged.addAll(existing);
        merged.add(ToolConfig.builder()
                .name(OCG_AGENT_TOOL_NAME)
                .toolType("agent_tool")
                .description(OCG_AGENT_TOOL_DESCRIPTION)
                .config(Map.of("workflowName", OcgAgentFactory.AGENT_NAME))
                .build());
        config.setTools(merged);
        return config;
    }
}
