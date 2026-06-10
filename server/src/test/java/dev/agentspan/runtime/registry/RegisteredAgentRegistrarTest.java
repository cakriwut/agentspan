/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.compiler.AgentCompiler;
import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;

/**
 * Unit tests for {@link RegisteredAgentRegistrar}.
 *
 * <p>Pins the contract every server-side sub-agent (OCG and any future
 * peer) relies on: declare a bean, the framework compiles it, stamps
 * auto-expose metadata if requested, and writes it to the metadata DAO.</p>
 */
class RegisteredAgentRegistrarTest {

    @Test
    void compilesAndRegistersEveryRegisteredAgent() {
        AgentCompiler compiler = mock(AgentCompiler.class);
        MetadataDAO dao = mock(MetadataDAO.class);
        when(compiler.compileWithoutAutoExpose(any())).thenAnswer(inv -> {
            AgentConfig cfg = inv.getArgument(0);
            WorkflowDef def = new WorkflowDef();
            def.setName(cfg.getName());
            return def;
        });

        RegisteredAgent a = stubAgent("alpha_agent", null);
        RegisteredAgent b = stubAgent("beta_agent", null);

        new RegisteredAgentRegistrar(compiler, dao, List.of(a, b)).registerAll();

        verify(compiler).compileWithoutAutoExpose(a.agentConfig());
        verify(compiler).compileWithoutAutoExpose(b.agentConfig());
        ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
        verify(dao, org.mockito.Mockito.times(2)).updateWorkflowDef(captor.capture());
        assertThat(captor.getAllValues().stream().map(WorkflowDef::getName))
                .containsExactlyInAnyOrder("alpha_agent", "beta_agent");
    }

    @Test
    void stampsAutoExposeMetadataWhenAgentRequestsIt() {
        // The stamp is what makes a registered agent LLM-visible to other
        // agents via AgentCompiler.mergeAutoExposedTools. Drift here would
        // silently hide every server-side sub-agent from end-user agents.
        AgentCompiler compiler = mock(AgentCompiler.class);
        MetadataDAO dao = mock(MetadataDAO.class);
        when(compiler.compileWithoutAutoExpose(any())).thenReturn(emptyDef("helper"));

        RegisteredAgent agent = stubAgent("helper", new ExposeAsTool("helper_tool", "Call when stuck."));

        new RegisteredAgentRegistrar(compiler, dao, List.of(agent)).registerAll();

        ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
        verify(dao).updateWorkflowDef(captor.capture());
        Object stamped = captor.getValue().getMetadata().get(AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY);
        assertThat(stamped).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) stamped;
        assertThat(spec).containsEntry("name", "helper_tool").containsEntry("description", "Call when stuck.");
    }

    @Test
    void doesNotStampWhenAutoExposeReturnsNull() {
        // A registered agent that exists server-side but isn't meant to be
        // LLM-visible (private helper, internal pipeline) must come back
        // from the DAO without the auto-expose flag.
        AgentCompiler compiler = mock(AgentCompiler.class);
        MetadataDAO dao = mock(MetadataDAO.class);
        when(compiler.compileWithoutAutoExpose(any())).thenReturn(emptyDef("internal"));

        new RegisteredAgentRegistrar(compiler, dao, List.of(stubAgent("internal", null))).registerAll();

        ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
        verify(dao).updateWorkflowDef(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        // metadata may be null OR present without the auto-expose key —
        // either way the LLM-visibility contract isn't tripped.
        if (metadata != null) {
            assertThat(metadata).doesNotContainKey(AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY);
        }
    }

    @Test
    void emptyAgentListIsANoOp() {
        AgentCompiler compiler = mock(AgentCompiler.class);
        MetadataDAO dao = mock(MetadataDAO.class);

        new RegisteredAgentRegistrar(compiler, dao, null).registerAll();
        new RegisteredAgentRegistrar(compiler, dao, List.of()).registerAll();

        // Neither compile nor write should fire — the registrar must be
        // benign when no RegisteredAgent beans are present (e.g. OCG off
        // and no future sub-agents declared).
        verifyNoInteractions(compiler);
        verifyNoInteractions(dao);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static RegisteredAgent stubAgent(String name, ExposeAsTool expose) {
        AgentConfig config = AgentConfig.builder().name(name).build();
        return new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return config;
            }

            @Override
            public ExposeAsTool autoExpose() {
                return expose;
            }
        };
    }

    private static WorkflowDef emptyDef(String name) {
        WorkflowDef def = new WorkflowDef();
        def.setName(name);
        def.setMetadata(new LinkedHashMap<>());
        return def;
    }
}
