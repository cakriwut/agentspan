/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        compiler = new AgentCompiler();
        metadataDAO = mock(MetadataDAO.class);
        ReflectionTestUtils.setField(compiler, "metadataDAO", metadataDAO);
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
        // Tests that construct AgentCompiler with `new AgentCompiler()`
        // (i.e. no Spring DI) must continue to work. The merger short-
        // circuits cleanly when metadataDAO is null instead of NPE-ing.
        AgentCompiler noDao = new AgentCompiler(); // no setField — metadataDAO stays null

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
