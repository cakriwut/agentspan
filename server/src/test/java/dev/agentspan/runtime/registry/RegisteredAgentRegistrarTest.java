/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

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
 * peer) relies on: declare a bean, the framework compiles it and writes
 * it to the metadata DAO so SUB_WORKFLOW dispatch resolves it by name.
 * LLM visibility is the merger's job, not the registrar's.</p>
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

        RegisteredAgent a = stubAgent("alpha_agent", new ExposeAsTool("alpha_tool", "first"));
        RegisteredAgent b = stubAgent("beta_agent", null);

        new RegisteredAgentRegistrar(compiler, dao, List.of(a, b)).registerAll();

        verify(compiler).compileWithoutAutoExpose(a.agentConfig());
        verify(compiler).compileWithoutAutoExpose(b.agentConfig());
        ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
        verify(dao, times(2)).updateWorkflowDef(captor.capture());
        assertThat(captor.getAllValues().stream().map(WorkflowDef::getName))
                .containsExactlyInAnyOrder("alpha_agent", "beta_agent");
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
}
