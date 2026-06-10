/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Unit tests for {@link AgentCompiler#mergeAutoExposedTools}.
 *
 * <p>Pins the generic auto-expose mechanism that lets any server-side
 * sub-agent become LLM-visible to every top-level agent just by stamping
 * a metadata flag on its {@link WorkflowDef}. These tests are intentionally
 * not OCG-specific — OCG is one consumer; the contract here is the
 * one every future consumer relies on.</p>
 */
class AutoExposedToolsMergeTest {

    private AgentCompiler compiler;
    private MetadataDAO metadataDAO;

    @BeforeEach
    void setUp() {
        metadataDAO = mock(MetadataDAO.class);
        compiler = new AgentCompiler(metadataDAO);
    }

    @Test
    void appendsFlaggedWorkflowAsAgentTool() {
        WorkflowDef flagged = wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "Use this when you need help.");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(flagged));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        compiler.mergeAutoExposedTools(config);

        assertThat(config.getTools()).hasSize(2);
        ToolConfig injected = config.getTools().get(1);
        assertThat(injected.getName()).isEqualTo("helper_agent");
        assertThat(injected.getToolType()).isEqualTo("agent_tool");
        // workflowName must match the registered WorkflowDef so SUB_WORKFLOW
        // dispatch at runtime resolves to the right workflow — drift here
        // would silently route to a missing workflow.
        assertThat(injected.getConfig()).containsEntry("workflowName", "_helper_agent");
        assertThat(injected.getDescription()).isEqualTo("Use this when you need help.");
    }

    @Test
    void skipsWorkflowsWithoutTheMetadataKey() {
        WorkflowDef plain = new WorkflowDef();
        plain.setName("_unrelated_workflow");
        plain.setMetadata(Map.of("some_other_key", "value"));
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(plain));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        compiler.mergeAutoExposedTools(config);

        // Only the user-declared tool remains; the unflagged workflow is
        // invisible to the merger.
        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getName()).isEqualTo("search");
    }

    @Test
    void doesNotInjectIntoTheAutoExposedWorkflowItself() {
        // Self-recursion guard: if the compile target IS the flagged
        // workflow, the merger must skip it. Without this, the OCG agent's
        // own compile would see itself in the DAO and recursively gain
        // itself as a tool — broken tool list + infinite delegation.
        WorkflowDef flagged = wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "irrelevant");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(flagged));

        AgentConfig config = AgentConfig.builder()
                .name("_helper_agent")
                .tools(new ArrayList<>(List.of(workerTool("internal_tool"))))
                .build();

        compiler.mergeAutoExposedTools(config);

        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getName()).isEqualTo("internal_tool");
    }

    @Test
    void doesNotDuplicateWhenAToolWithThatNameAlreadyExists() {
        // The caller's explicit declaration wins. Two entries with the
        // same name would confuse the LLM's tool spec list and cause both
        // dispatches to resolve to the same workflow.
        WorkflowDef flagged = wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "auto description");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(flagged));

        ToolConfig existing = ToolConfig.builder()
                .name("helper_agent")
                .toolType("agent_tool")
                .description("user-provided description")
                .build();
        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(existing)))
                .build();

        compiler.mergeAutoExposedTools(config);

        assertThat(config.getTools()).hasSize(1);
        assertThat(config.getTools().get(0).getDescription()).isEqualTo("user-provided description");
    }

    @Test
    void noOpWhenMetadataDaoIsAbsent() {
        // Tests that construct AgentCompiler without a metadataDAO must
        // continue to work. The no-arg constructor exists for this path;
        // the merger short-circuits cleanly instead of NPE-ing.
        AgentCompiler noDao = new AgentCompiler(); // null metadataDAO

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>(List.of(workerTool("search"))))
                .build();

        noDao.mergeAutoExposedTools(config);

        assertThat(config.getTools()).hasSize(1);
    }

    @Test
    void appendsMultipleFlaggedWorkflowsInDaoOrder() {
        WorkflowDef a = wfWithAutoExposeMetadata("_a_agent", "alpha_agent", "first");
        WorkflowDef b = wfWithAutoExposeMetadata("_b_agent", "beta_agent", "second");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(a, b));

        AgentConfig config = AgentConfig.builder()
                .name("user_agent")
                .tools(new ArrayList<>())
                .build();

        compiler.mergeAutoExposedTools(config);

        // Both flagged workflows surface as agent_tools on the same config.
        // This is the path that lets future server-side capabilities
        // accumulate without any per-feature wiring.
        assertThat(config.getTools()).hasSize(2);
        assertThat(config.getTools().get(0).getName()).isEqualTo("alpha_agent");
        assertThat(config.getTools().get(1).getName()).isEqualTo("beta_agent");
    }

    @Test
    void mergeRunsOnceAtTopLevelOnlyAndSkipsInternalRecursion() {
        // Pinning the contract that the public ``compile()`` is the only entry
        // that runs the auto-expose merge. Internal recursion (compileSubAgent,
        // graph-structure subgraph compile, MultiAgentCompiler swarm) must go
        // through the non-merging entry so nested specialist sub-agents don't
        // silently pick up unrelated server-side tools.
        WorkflowDef flagged = wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "Use when stuck.");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(flagged));

        AgentConfig inner = AgentConfig.builder()
                .name("inner_specialist")
                .model("openai/gpt-4o-mini")
                .build();

        // compileSubAgent is the entry that nested compilation goes through.
        // It must NOT mutate ``inner.tools`` with the auto-exposed entry.
        compiler.compileSubAgent(
                inner, "inner_ref", "${workflow.input.prompt}", "${workflow.input.media}", null);

        boolean innerHasAutoExposed = inner.getTools() != null
                && inner.getTools().stream().anyMatch(t -> "helper_agent".equals(t.getName()));
        assertThat(innerHasAutoExposed)
                .as("nested sub-agent must NOT have the auto-exposed tool merged into its tool list")
                .isFalse();
    }

    @Test
    void daoQueriedOnlyOnceAcrossMultipleMerges() {
        // Lazy cache: registered server-side agents are written at @PostConstruct
        // and don't change at runtime, so the per-compile DAO fetch is wasted
        // work after the first one. Pin the contract so anyone removing the
        // cache trips this test.
        WorkflowDef flagged = wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "x");
        when(metadataDAO.getAllWorkflowDefsLatestVersions()).thenReturn(List.of(flagged));

        AgentConfig a = AgentConfig.builder()
                .name("agent_a")
                .tools(new ArrayList<>())
                .build();
        AgentConfig b = AgentConfig.builder()
                .name("agent_b")
                .tools(new ArrayList<>())
                .build();

        compiler.mergeAutoExposedTools(a);
        compiler.mergeAutoExposedTools(b);

        verify(metadataDAO, times(1)).getAllWorkflowDefsLatestVersions();
        // Both configs still received the merge from the cached result.
        assertThat(a.getTools()).extracting(ToolConfig::getName).contains("helper_agent");
        assertThat(b.getTools()).extracting(ToolConfig::getName).contains("helper_agent");
    }

    @Test
    void daoFailureIsNotCachedAndIsRetriedOnNextMerge() {
        // Caching the failure path would turn a transient blip into a
        // permanent silent loss of the merge. Stub: first call throws, second
        // call returns a flagged def. The second merge must pick it up.
        when(metadataDAO.getAllWorkflowDefsLatestVersions())
                .thenThrow(new RuntimeException("transient DAO failure"))
                .thenReturn(List.of(wfWithAutoExposeMetadata("_helper_agent", "helper_agent", "x")));

        AgentConfig first = AgentConfig.builder()
                .name("first")
                .tools(new ArrayList<>())
                .build();
        AgentConfig second = AgentConfig.builder()
                .name("second")
                .tools(new ArrayList<>())
                .build();

        compiler.mergeAutoExposedTools(first);
        compiler.mergeAutoExposedTools(second);

        // First merge happened during the transient failure → no auto-expose.
        assertThat(first.getTools()).extracting(ToolConfig::getName).doesNotContain("helper_agent");
        // Second merge re-queried the DAO and picked the entry up.
        assertThat(second.getTools()).extracting(ToolConfig::getName).contains("helper_agent");
        verify(metadataDAO, times(2)).getAllWorkflowDefsLatestVersions();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static WorkflowDef wfWithAutoExposeMetadata(String workflowName, String toolName, String description) {
        WorkflowDef def = new WorkflowDef();
        def.setName(workflowName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
                AgentCompiler.AUTO_EXPOSE_AS_TOOL_METADATA_KEY, Map.of("name", toolName, "description", description));
        def.setMetadata(metadata);
        return def;
    }

    private static ToolConfig workerTool(String name) {
        return ToolConfig.builder().name(name).toolType("worker").build();
    }
}
