/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;
import dev.agentspan.runtime.registry.RegisteredAgent;
import dev.agentspan.runtime.registry.RegisteredAgent.ExposeAsTool;

/**
 * Unit tests for {@link AutoExposedToolsMerger}.
 *
 * <p>Pins the generic auto-expose mechanism that lets any server-side
 * sub-agent become LLM-visible to every top-level agent just by declaring
 * a {@link RegisteredAgent} bean with a non-null {@code autoExpose()}.
 * These tests are intentionally not OCG-specific — OCG is one consumer;
 * the contract here is the one every future consumer relies on.</p>
 */
class AutoExposedToolsMergeTest {

    @Test
    void appendsRegisteredAgentAsAgentTool() {
        AutoExposedToolsMerger merger = new AutoExposedToolsMerger(
                List.of(registeredAgent("_helper_agent", "helper_agent", "Use this when you need help.")));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        merger.merge(config);

        assertThat(config.getTools()).hasSize(2);
        ToolConfig injected = config.getTools().get(1);
        assertThat(injected.getName()).isEqualTo("helper_agent");
        assertThat(injected.getToolType()).isEqualTo("agent_tool");
        // workflowName must match the WorkflowDef the registrar persists so
        // SUB_WORKFLOW dispatch at runtime resolves to the right workflow —
        // drift here would silently route to a missing workflow.
        assertThat(injected.getConfig()).containsEntry("workflowName", "_helper_agent");
        assertThat(injected.getDescription()).isEqualTo("Use this when you need help.");
    }

    @Test
    void skipsAgentsThatDoNotRequestAutoExpose() {
        // autoExpose() == null means "register the workflow but keep it
        // invisible to user agents" (private helper, internal pipeline).
        RegisteredAgent privateAgent = new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return AgentConfig.builder().name("_private_agent").build();
            }
        };
        AutoExposedToolsMerger merger = new AutoExposedToolsMerger(List.of(privateAgent));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        merger.merge(config);

        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getName()).isEqualTo("search");
    }

    @Test
    void doesNotInjectIntoTheAutoExposedWorkflowItself() {
        // Self-recursion guard: if the compile target IS the registered
        // agent, the merger must skip it. Without this, the OCG agent's own
        // compile would gain itself as a tool — broken tool list + infinite
        // delegation.
        AutoExposedToolsMerger merger =
                new AutoExposedToolsMerger(List.of(registeredAgent("_helper_agent", "helper_agent", "irrelevant")));

        AgentConfig config = AgentConfig.builder()
                .name("_helper_agent")
                .tools(new ArrayList<>(List.of(workerTool("internal_tool"))))
                .build();

        merger.merge(config);

        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getName()).isEqualTo("internal_tool");
    }

    @Test
    void doesNotDuplicateWhenAToolWithThatNameAlreadyExists() {
        // The caller's explicit declaration wins. Two entries with the
        // same name would confuse the LLM's tool spec list and cause both
        // dispatches to resolve to the same workflow.
        AutoExposedToolsMerger merger = new AutoExposedToolsMerger(
                List.of(registeredAgent("_helper_agent", "helper_agent", "auto description")));

        ToolConfig existing = ToolConfig.builder()
                .name("helper_agent")
                .toolType("agent_tool")
                .description("user-provided description")
                .build();
        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(existing)))
                .build();

        merger.merge(config);

        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getDescription()).isEqualTo("user-provided description");
    }

    @Test
    void noOpWhenNoRegisteredAgentsArePresent() {
        // The no-arg AgentCompiler constructor (used throughout the test
        // suite) wires a disabled merger — compiles must work untouched.
        AgentCompiler compiler = new AgentCompiler();

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .model("openai/gpt-4o-mini")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        compiler.compile(config);

        assertThat(config.getTools()).hasSize(1);
    }

    @Test
    void appendsMultipleAgentsInBeanOrder() {
        AutoExposedToolsMerger merger = new AutoExposedToolsMerger(List.of(
                registeredAgent("_a_agent", "alpha_agent", "first"),
                registeredAgent("_b_agent", "beta_agent", "second")));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>())
                .build();

        merger.merge(config);

        // Both registered agents surface as agent_tools on the same config.
        // This is the path that lets future server-side capabilities
        // accumulate without any per-feature wiring.
        assertThat(config.getTools()).hasSize(2);
        assertThat(config.getTools().get(0).getName()).isEqualTo("alpha_agent");
        assertThat(config.getTools().get(1).getName()).isEqualTo("beta_agent");
    }

    @Test
    void compileEntryRunsTheMerge() {
        // The public ``compile()`` is the single place user agents pick up
        // auto-exposed tools — pin that the wiring from AgentCompiler into
        // the merger actually fires.
        AgentCompiler compiler = new AgentCompiler(
                new AutoExposedToolsMerger(List.of(registeredAgent("_helper_agent", "helper_agent", "x"))));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .model("openai/gpt-4o-mini")
                .tools(new ArrayList<>())
                .build();

        compiler.compile(config);

        assertThat(config.getTools()).extracting(ToolConfig::getName).contains("helper_agent");
    }

    @Test
    void mergeRunsOnceAtTopLevelOnlyAndSkipsInternalRecursion() {
        // Pinning the contract that the public ``compile()`` is the only entry
        // that runs the auto-expose merge. Internal recursion (compileSubAgent,
        // graph-structure subgraph compile, MultiAgentCompiler swarm) must go
        // through the non-merging entry so nested specialist sub-agents don't
        // silently pick up unrelated server-side tools.
        AgentCompiler compiler = new AgentCompiler(new AutoExposedToolsMerger(
                List.of(registeredAgent("_helper_agent", "helper_agent", "Use when stuck."))));

        AgentConfig inner = AgentConfig.builder()
                .name("inner_specialist")
                .model("openai/gpt-4o-mini")
                .build();

        // compileSubAgent is the entry that nested compilation goes through.
        // It must NOT mutate ``inner.tools`` with the auto-exposed entry.
        compiler.compileSubAgent(inner, "inner_ref", "${workflow.input.prompt}", "${workflow.input.media}", null);

        boolean innerHasAutoExposed =
                inner.getTools() != null && inner.getTools().stream().anyMatch(t -> "helper_agent".equals(t.getName()));
        assertThat(innerHasAutoExposed)
                .as("nested sub-agent must NOT have the auto-exposed tool merged into its tool list")
                .isFalse();
    }

    @Test
    void agentConfigReadOnceAtConstructionNotPerMerge() {
        // Entries are fixed when the merger is constructed from the bean
        // list — per-merge re-reads would be wasted work (registered agents
        // don't change at runtime) and would re-trigger any validation in
        // the agent's config factory on every user compile.
        AtomicInteger reads = new AtomicInteger();
        RegisteredAgent counting = new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                reads.incrementAndGet();
                return AgentConfig.builder().name("_helper_agent").build();
            }

            @Override
            public ExposeAsTool autoExpose() {
                return new ExposeAsTool("helper_agent", "x");
            }
        };
        AutoExposedToolsMerger merger = new AutoExposedToolsMerger(List.of(counting));

        merger.merge(AgentConfig.builder().name("a").tools(new ArrayList<>()).build());
        merger.merge(AgentConfig.builder().name("b").tools(new ArrayList<>()).build());

        assertThat(reads).hasValue(1);
    }

    @Test
    void blankToolNameFailsFastAtConstruction() {
        // A blank LLM-facing tool name is a programming error in the
        // RegisteredAgent bean — surfacing it at boot beats silently
        // registering an unusable tool.
        RegisteredAgent broken = new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return AgentConfig.builder().name("_broken_agent").build();
            }

            @Override
            public ExposeAsTool autoExpose() {
                return new ExposeAsTool("  ", "description");
            }
        };

        assertThatThrownBy(() -> new AutoExposedToolsMerger(List.of(broken)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank tool name");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static RegisteredAgent registeredAgent(String workflowName, String toolName, String description) {
        return new RegisteredAgent() {
            @Override
            public AgentConfig agentConfig() {
                return AgentConfig.builder().name(workflowName).build();
            }

            @Override
            public ExposeAsTool autoExpose() {
                return new ExposeAsTool(toolName, description);
            }
        };
    }

    private static ToolConfig workerTool(String name) {
        return ToolConfig.builder().name(name).toolType("worker").build();
    }
}
