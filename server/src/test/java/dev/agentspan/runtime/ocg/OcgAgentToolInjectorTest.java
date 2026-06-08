/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Unit tests pinning {@link OcgAgentToolInjector}'s injection rules.
 *
 * <p>The injector decides whether the OCG sub-agent gets attached as an
 * {@code agent_tool} on every compiled top-level agent. Each rule below is
 * a load-bearing safety property — if any of them slips, the main agent
 * either misses OCG entirely (the user-visible bug) or recurses into
 * itself / picks up duplicate tools (silent state corruption).</p>
 */
class OcgAgentToolInjectorTest {

    @Test
    void appendsOcgAgentToolAsLastEntryWhenEnabled() {
        AgentConfig cfg = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        AgentConfig out = OcgAgentToolInjector.inject(cfg, /*ocgEnabled=*/ true);

        assertThat(out.getTools()).hasSize(2);
        ToolConfig injected = out.getTools().get(1);
        assertThat(injected.getName()).isEqualTo("ocg_agent");
        assertThat(injected.getToolType()).isEqualTo("agent_tool");
        // workflowName must point at the registered _ocg_agent — otherwise
        // ToolCompiler will fabricate ``ocg_agent_agent_wf`` and the
        // SUB_WORKFLOW dispatch hits a missing workflow at runtime.
        assertThat(injected.getConfig()).containsEntry("workflowName", "_ocg_agent");
    }

    @Test
    void skipsInjectionWhenOcgDisabled() {
        AgentConfig cfg = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        AgentConfig out = OcgAgentToolInjector.inject(cfg, /*ocgEnabled=*/ false);

        assertThat(out.getTools()).hasSize(1);
        assertThat(out.getTools().get(0).getName()).isEqualTo("search");
    }

    @Test
    void doesNotSelfInjectIntoTheOcgAgentItself() {
        // Without this guard the OCG sub-agent would gain itself as a tool
        // and the LLM could recurse: ocg_agent → ocg_agent → ocg_agent → …
        AgentConfig cfg = AgentConfig.builder()
                .name(OcgAgentFactory.AGENT_NAME)
                .tools(new ArrayList<>(OcgAgentFactory.buildTools()))
                .build();

        int before = cfg.getTools().size();
        AgentConfig out = OcgAgentToolInjector.inject(cfg, /*ocgEnabled=*/ true);

        assertThat(out.getTools()).hasSize(before);
        assertThat(out.getTools().stream().map(ToolConfig::getName)).doesNotContain("ocg_agent");
    }

    @Test
    void doesNotDuplicateWhenOcgAgentToolAlreadyPresent() {
        // If a user (or a re-entrant compile) has already added an
        // ocg_agent entry, a second pass must not silently append a
        // duplicate — both would map to the same workflowName and confuse
        // the LLM's tool spec list.
        ToolConfig existing = ToolConfig.builder()
                .name("ocg_agent")
                .toolType("agent_tool")
                .description("user-provided")
                .build();
        AgentConfig cfg = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(existing)))
                .build();

        AgentConfig out = OcgAgentToolInjector.inject(cfg, /*ocgEnabled=*/ true);

        assertThat(out.getTools()).hasSize(1);
        assertThat(out.getTools().get(0).getDescription()).isEqualTo("user-provided");
    }

    @Test
    void handlesNullToolsListByCreatingOne() {
        AgentConfig cfg = AgentConfig.builder().name("user_agent").tools(null).build();

        AgentConfig out = OcgAgentToolInjector.inject(cfg, /*ocgEnabled=*/ true);

        assertThat(out.getTools()).hasSize(1);
        assertThat(out.getTools().get(0).getName()).isEqualTo("ocg_agent");
    }

    private static ToolConfig workerTool(String name) {
        return ToolConfig.builder().name(name).toolType("worker").build();
    }
}
