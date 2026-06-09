/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
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
        registerOcgTaskDefs();
        AgentConfig config = OcgAgentFactory.build(properties);
        WorkflowDef def = agentCompiler.compile(config);

        // Stamp the auto-expose marker so AgentCompiler.mergeAutoExposedTools
        // appends this workflow as an `ocg_agent` agent_tool on every
        // subsequent top-level compile. This is the only line that ties OCG
        // to LLM visibility — the rest is generic compiler machinery.
        Map<String, Object> metadata =
                def.getMetadata() != null ? new LinkedHashMap<>(def.getMetadata()) : new LinkedHashMap<>();
        metadata.put(
                AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY,
                Map.of("name", OcgAgentFactory.TOOL_NAME, "description", OcgAgentFactory.TOOL_DESCRIPTION));
        def.setMetadata(metadata);

        metadataDAO.updateWorkflowDef(def);
        log.info(
                "OCG sub-agent registered: workflow='{}' tool='{}' model='{}' url='{}'",
                def.getName(),
                OcgAgentFactory.TOOL_NAME,
                properties.getModel(),
                properties.getUrl());
    }

    /**
     * Register a {@link TaskDef} for each OCG tool name so Conductor's dynamic
     * dispatch can resolve the task at runtime.
     *
     * <p>The enrichment script (see {@code JavaScriptBuilder.enrichToolsScript})
     * emits dynamic tasks with {@code name=<lowercase tool name>} and
     * {@code type=OCG_*}. Conductor looks up the task by <em>name</em> in the
     * TaskDef registry before dispatching — without a matching def, the
     * SUB_WORKFLOW fails with {@code "Cannot find task by name ocg_query in
     * the task definitions"} and the parent LLM loop sees an opaque error.</p>
     *
     * <p>{@code retryCount=0} on purpose: each OCG call is a stateless HTTP
     * round-trip handled by {@link OcgRequestTask}; retries here would double
     * the load and bypass the parent LLM's ability to refine the query.</p>
     */
    private void registerOcgTaskDefs() {
        List<String> names = List.of(
                "ocg_query",
                "ocg_get_entity",
                "ocg_neighborhood",
                "ocg_code_history",
                "ocg_memory_set",
                "ocg_memory_reinforce",
                "ocg_memory_delete");
        for (String name : names) {
            TaskDef def = new TaskDef();
            def.setName(name);
            def.setRetryCount(0);
            def.setTimeoutSeconds(60);
            def.setResponseTimeoutSeconds(60);
            def.setOwnerEmail("ocg@agentspan.dev");
            metadataDAO.updateTaskDef(def);
        }
        log.info("OCG TaskDefs registered: {}", names);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
