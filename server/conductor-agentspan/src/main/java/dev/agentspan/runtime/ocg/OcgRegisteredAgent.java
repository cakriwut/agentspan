/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.registry.RegisteredAgent;

import lombok.RequiredArgsConstructor;

/**
 * OCG's contribution to the {@link RegisteredAgent} registry: the
 * server-registered sub-agent that the main agent's LLM can delegate to
 * for context retrieval.
 *
 * <p>Picked up automatically by {@code RegisteredAgentRegistrar} on boot,
 * compiled to a {@code WorkflowDef}, stamped with the auto-expose
 * metadata marker (via {@link #autoExpose()}), and written to the
 * metadata store. From the next user-agent compile onward,
 * {@code ocg_agent} appears in every LLM's tool list.</p>
 *
 * <p>The {@code @ConditionalOnExpression} uses {@code .length() > 0}
 * rather than the more obvious {@code @ConditionalOnProperty} because
 * the latter treats an empty string as "present and not false" and
 * would instantiate this bean for unset {@code OCG_URL}, breaking the
 * tests that rely on OCG being off by default.</p>
 */
@Component
@ConditionalOnExpression("'${agentspan.ocg.url:}'.length() > 0")
@RequiredArgsConstructor
public class OcgRegisteredAgent implements RegisteredAgent {

    private final OcgProperties properties;

    @Override
    public AgentConfig agentConfig() {
        return OcgAgentFactory.build(properties);
    }

    @Override
    public ExposeAsTool autoExpose() {
        return new ExposeAsTool(OcgAgentFactory.TOOL_NAME, OcgAgentFactory.TOOL_DESCRIPTION);
    }
}
