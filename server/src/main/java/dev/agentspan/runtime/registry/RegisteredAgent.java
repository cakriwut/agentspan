/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import dev.agentspan.runtime.model.AgentConfig;

/**
 * Marker for a Spring bean that contributes a server-registered agent.
 *
 * <p>Any {@code @Bean} declared as {@link RegisteredAgent} is picked up by
 * {@link RegisteredAgentRegistrar} on startup, compiled into a
 * {@code WorkflowDef}, and persisted to Conductor's metadata store. Adding
 * a new server-side sub-agent is therefore a one-bean change — no
 * per-feature {@code @PostConstruct}, no manual {@code MetadataDAO}
 * write, no duplication of the compile/persist ceremony.</p>
 *
 * <p>Implementations should be stateless or read configuration via Spring
 * injection. {@link #agentConfig()} must be pure — it is invoked at
 * startup by both the registrar (to compile and persist) and
 * {@code AutoExposedToolsMerger} (to read the workflow name).</p>
 */
public interface RegisteredAgent {

    /**
     * The agent definition to compile and register. The returned
     * {@link AgentConfig} owns name, model, instructions, tools — the
     * registrar does not touch any of these fields.
     */
    AgentConfig agentConfig();

    /**
     * When non-null, {@code AutoExposedToolsMerger} reads this spec
     * directly from the bean and appends the agent as an
     * {@code agent_tool} on every top-level user-agent compile. Return
     * {@code null} to register the workflow without exposing it as a
     * tool.
     */
    default ExposeAsTool autoExpose() {
        return null;
    }

    /**
     * The LLM-facing name and description used by the auto-expose path.
     * The name is what users' agents will see in their tool spec list;
     * the description is the LLM's only hint about <em>when</em> to
     * delegate.
     */
    record ExposeAsTool(String toolName, String toolDescription) {}
}
