/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;
import dev.agentspan.runtime.registry.RegisteredAgent;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;

/**
 * Owns the "auto-expose registered sub-agents as tools" contract for
 * {@link AgentCompiler}.
 *
 * <p>The contract: any {@link RegisteredAgent} bean whose
 * {@link RegisteredAgent#autoExpose()} is non-null is silently appended to
 * every top-level agent's tool list as an {@code agent_tool} at compile
 * time. The OCG sub-agent (and any future server-registered sub-agent)
 * becomes LLM-visible via this single mechanism — no per-feature injection
 * class required.</p>
 *
 * <p>The entries are read straight from the Spring-managed bean list at
 * construction time. The beans are also what {@code RegisteredAgentRegistrar}
 * persists to the metadata store, so both sides share one source of truth
 * and no DAO read-back is needed: the merger is complete the moment it is
 * constructed, regardless of when the registrar's {@code @PostConstruct}
 * writes happen.</p>
 */
@Component
public class AutoExposedToolsMerger {

    private static final Logger log = LoggerFactory.getLogger(AutoExposedToolsMerger.class);

    /**
     * Pairing of source workflow name + pre-built {@code agent_tool}
     * {@link ToolConfig}, fixed at construction. The workflow name rides
     * alongside the tool so the per-compile self-recursion guard works
     * without re-reading the {@link AgentConfig}.
     */
    private record AutoExposedEntry(String workflowName, ToolConfig tool) {}

    private final List<AutoExposedEntry> entries;

    @Autowired
    public AutoExposedToolsMerger(@Autowired(required = false) List<RegisteredAgent> registeredAgents) {
        this.entries = buildEntries(registeredAgents != null ? registeredAgents : List.of());
    }

    /** A merger with no registered agents — always a no-op. For tests / direct construction. */
    public static AutoExposedToolsMerger disabled() {
        return new AutoExposedToolsMerger(null);
    }

    /**
     * Append every auto-exposed registered agent to {@code config.tools}
     * as an {@code agent_tool}.
     *
     * <p>Mutates {@code config} in place. Skips when:</p>
     * <ul>
     *   <li>No {@link RegisteredAgent} bean requested auto-expose</li>
     *   <li>The agent being compiled IS the auto-exposed one
     *       — no self-recursion</li>
     *   <li>A tool with that name is already declared on the config
     *       — caller's explicit declaration wins</li>
     * </ul>
     */
    public void merge(AgentConfig config) {
        if (config == null || entries.isEmpty()) return;

        Set<String> takenNames = collectToolNames(config);
        List<ToolConfig> toAppend = new ArrayList<>();
        for (AutoExposedEntry entry : entries) {
            if (entry.workflowName().equals(config.getName())) continue; // self-recursion guard
            if (!takenNames.add(entry.tool().getName())) continue; // caller's declaration wins
            toAppend.add(entry.tool());
            log.info(
                    "Auto-exposed workflow '{}' as agent_tool '{}' on '{}'",
                    entry.workflowName(),
                    entry.tool().getName(),
                    config.getName());
        }
        if (!toAppend.isEmpty()) {
            appendTools(config, toAppend);
        }
    }

    private static List<AutoExposedEntry> buildEntries(List<RegisteredAgent> registeredAgents) {
        List<AutoExposedEntry> built = new ArrayList<>();
        for (RegisteredAgent agent : registeredAgents) {
            ExposeAsTool expose = agent.autoExpose();
            if (expose == null) continue;
            if (expose.toolName() == null || expose.toolName().isBlank()) {
                throw new IllegalStateException("RegisteredAgent "
                        + agent.getClass().getName() + " requested auto-expose with a blank tool name");
            }
            String workflowName = agent.agentConfig().getName();
            built.add(new AutoExposedEntry(workflowName, buildAgentTool(workflowName, expose)));
        }
        return List.copyOf(built);
    }

    private static Set<String> collectToolNames(AgentConfig config) {
        if (config.getTools() == null) return new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ToolConfig t : config.getTools()) {
            if (t.getName() != null) names.add(t.getName());
        }
        return names;
    }

    private static ToolConfig buildAgentTool(String workflowName, ExposeAsTool expose) {
        return ToolConfig.builder()
                .name(expose.toolName())
                .toolType("agent_tool")
                .description(expose.toolDescription() != null ? expose.toolDescription() : "")
                .config(Map.of("workflowName", workflowName))
                .build();
    }

    private static void appendTools(AgentConfig config, List<ToolConfig> toAppend) {
        List<ToolConfig> merged = new ArrayList<>(config.getTools() != null ? config.getTools() : List.of());
        merged.addAll(toAppend);
        config.setTools(merged);
    }
}
