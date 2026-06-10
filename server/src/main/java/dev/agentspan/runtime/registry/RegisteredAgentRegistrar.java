/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * the same compile → (optional auto-expose stamp) → persist pipeline on
 * server startup.
 *
 * <p>Replaces feature-specific {@code @PostConstruct registerWorkflow()}
 * methods that previously coupled OCG (and future sub-agents) to the
 * mechanics of metadata-store writes. Adding a new server-side sub-agent
 * now only requires declaring a {@code @Bean RegisteredAgent}.</p>
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
        // ``compileWithoutAutoExpose`` (not ``compile``) for two reasons:
        //   1. Registered agents shouldn't have other registered agents
        //      auto-injected into them as tools.
        //   2. The merger's lazy cache must not be triggered here — the
        //      registered defs aren't in the DAO yet at this point, so the
        //      first read would snapshot an empty list and freeze it for the
        //      bean's lifetime, silently hiding every registered agent from
        //      subsequent user compiles.
        WorkflowDef def = agentCompiler.compileWithoutAutoExpose(config);
        ExposeAsTool expose = agent.autoExpose();
        if (expose != null) {
            stampAutoExpose(def, expose);
        }
        metadataDAO.updateWorkflowDef(def);
        log.info(
                "Registered agent: workflow='{}'{}",
                def.getName(),
                expose != null ? " autoExposeAs='" + expose.toolName() + "'" : "");
    }

    private static void stampAutoExpose(WorkflowDef def, ExposeAsTool expose) {
        Map<String, Object> metadata =
                def.getMetadata() != null ? new LinkedHashMap<>(def.getMetadata()) : new LinkedHashMap<>();
        metadata.put(
                AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY,
                Map.of("name", expose.toolName(), "description", expose.toolDescription()));
        def.setMetadata(metadata);
    }
}
