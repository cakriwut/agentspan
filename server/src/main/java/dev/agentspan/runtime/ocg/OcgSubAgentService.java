/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.compiler.AgentCompiler;
import dev.agentspan.runtime.model.AgentConfig;

import lombok.RequiredArgsConstructor;

/**
 * Registers the OCG sub-agent workflow at startup (when
 * {@code agentspan.ocg.url} is set).
 *
 * <p>The agent itself is built by {@link OcgAgentFactory}, compiled by the
 * standard {@link AgentCompiler}, and persisted via {@link MetadataDAO}. From
 * Conductor's perspective it is just another workflow named
 * {@code _ocg_agent}.</p>
 *
 * <p>Main-agent invocation happens via an {@code agent_tool} that
 * {@code AgentService} auto-injects into every top-level agent — the main
 * agent's LLM decides whether and when to call it. This service no longer
 * dispatches OCG itself; it only owns the workflow's registration.</p>
 */
@Service
@ConditionalOnProperty(prefix = "agentspan.ocg", name = "url")
@RequiredArgsConstructor
public class OcgSubAgentService {

    private static final Logger log = LoggerFactory.getLogger(OcgSubAgentService.class);

    private final OcgProperties properties;
    private final AgentCompiler agentCompiler;
    private final MetadataDAO metadataDAO;

    @PostConstruct
    public void registerWorkflow() {
        if (!properties.isEnabled()) {
            // Defensive — the @ConditionalOnProperty guard means we shouldn't
            // be here, but keep the check so unit tests can instantiate this
            // service directly with a disabled config without crashing.
            return;
        }
        AgentConfig config = OcgAgentFactory.build(properties);
        WorkflowDef def = agentCompiler.compile(config);
        metadataDAO.updateWorkflowDef(def);
        log.info(
                "OCG sub-agent registered: workflow='{}' model='{}' url='{}'",
                def.getName(),
                properties.getModel(),
                properties.getUrl());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
