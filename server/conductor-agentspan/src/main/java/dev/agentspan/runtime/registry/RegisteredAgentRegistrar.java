/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.compiler.AgentCompiler;
import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;

/**
 * Generic registrar that drives every {@link RegisteredAgent} bean through
 * the same compile → persist pipeline on server startup.
 *
 * <p>Replaces feature-specific {@code @PostConstruct registerWorkflow()}
 * methods that previously coupled OCG (and future sub-agents) to the
 * mechanics of metadata-store writes. Adding a new server-side sub-agent
 * now only requires declaring a {@code @Bean RegisteredAgent}.</p>
 *
 * <p>LLM visibility is not this class's job: {@code AutoExposedToolsMerger}
 * reads {@link RegisteredAgent#autoExpose()} straight from the bean list,
 * so the persisted {@link WorkflowDef} only needs to exist for
 * SUB_WORKFLOW dispatch to resolve it by name at runtime.</p>
 */
@Component
@DependsOn("registeredTaskDefsRegistrar")
public class RegisteredAgentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RegisteredAgentRegistrar.class);

    private final AgentCompiler agentCompiler;
    private final MetadataDAO metadataDAO;
    private final List<RegisteredAgent> registeredAgents;

    @Autowired
    public RegisteredAgentRegistrar(
            AgentCompiler agentCompiler,
            MetadataDAO metadataDAO,
            @Autowired(required = false) List<RegisteredAgent> registeredAgents) {
        this.agentCompiler = agentCompiler;
        this.metadataDAO = metadataDAO;
        this.registeredAgents = registeredAgents != null ? registeredAgents : List.of();
    }

    @PostConstruct
    public void registerAll() {
        for (RegisteredAgent agent : registeredAgents) {
            register(agent);
        }
        if (!registeredAgents.isEmpty()) {
            log.info("Registered {} server-side agent(s)", registeredAgents.size());
        }
    }

    private void register(RegisteredAgent agent) {
        AgentConfig config = agent.agentConfig();
        // ``compileWithoutAutoExpose`` (not ``compile``) — registered agents
        // shouldn't have other registered agents auto-injected into them as
        // tools.
        WorkflowDef def = agentCompiler.compileWithoutAutoExpose(config);
        metadataDAO.updateWorkflowDef(def);
        ExposeAsTool expose = agent.autoExpose();
        log.info(
                "Registered agent: workflow='{}'{}",
                def.getName(),
                expose != null ? " autoExposeAs='" + expose.toolName() + "'" : "");
    }
}
