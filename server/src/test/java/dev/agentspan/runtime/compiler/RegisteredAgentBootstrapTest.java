/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;
import dev.agentspan.runtime.registry.RegisteredAgent;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;
import dev.agentspan.runtime.registry.RegisteredAgentRegistrar;

/**
 * Bootstrap-ordering test for the {@link RegisteredAgentRegistrar}
 * + auto-expose merger interaction.
 *
 * <p>The merger reads its entries straight from the {@link RegisteredAgent}
 * bean list at construction, so a user compile sees every registered agent
 * regardless of whether the registrar's {@code @PostConstruct} DAO writes
 * have happened yet. (The previous DAO-scan design could snapshot an empty
 * cache during bootstrap and silently hide every registered agent for the
 * bean's lifetime — this test pins that the trap is structurally gone.)</p>
 */
class RegisteredAgentBootstrapTest {

    @Test
    void userCompileSeesRegisteredAgentsRegardlessOfRegistrarOrdering() {
        MetadataDAO dao = mock(MetadataDAO.class);
        List<WorkflowDef> daoState = new ArrayList<>();
        doAnswer(inv -> {
                    daoState.add(inv.getArgument(0));
                    return null;
                })
                .when(dao)
                .updateWorkflowDef(any(WorkflowDef.class));

        RegisteredAgent helper = new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return AgentConfig.builder()
                        .name("_helper_agent")
                        .description("Test helper")
                        .model("openai/gpt-4o-mini")
                        .tools(new ArrayList<>())
                        .build();
            }

            @Override
            public ExposeAsTool autoExpose() {
                return new ExposeAsTool("helper_tool", "Call when stuck.");
            }
        };

        // Production wiring: the merger is built from the same bean list the
        // registrar iterates.
        AgentCompiler compiler = new AgentCompiler(new AutoExposedToolsMerger(List.of(helper)));

        // A compile BEFORE the registrar has written anything to the DAO —
        // the exact window where the old DAO-scan design froze an empty
        // cache — must already see the registered agent.
        AgentConfig earlyAgent = AgentConfig.builder()
                .name("early_agent")
                .model("openai/gpt-4o-mini")
                .tools(new ArrayList<>())
                .build();
        compiler.compile(earlyAgent);
        assertThat(earlyAgent.getTools())
                .extracting(ToolConfig::getName)
                .as("compile before registrar bootstrap must already see the registered agent")
                .contains("helper_tool");

        // Bootstrap: the registrar persists the WorkflowDef so SUB_WORKFLOW
        // dispatch can resolve '_helper_agent' by name at runtime.
        new RegisteredAgentRegistrar(compiler, dao, List.of(helper)).registerAll();
        assertThat(daoState).hasSize(1);
        assertThat(daoState.get(0).getName()).isEqualTo("_helper_agent");

        // And a compile after bootstrap sees it too, of course.
        AgentConfig lateAgent = AgentConfig.builder()
                .name("late_agent")
                .model("openai/gpt-4o-mini")
                .tools(new ArrayList<>())
                .build();
        compiler.compile(lateAgent);
        assertThat(lateAgent.getTools()).extracting(ToolConfig::getName).contains("helper_tool");
    }
}
