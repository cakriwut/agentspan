/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Unit tests for {@link OcgAgentFactory}.
 *
 * <p>Pins the agent's surface area — name, model wiring, tool count, tool
 * name → toolType mapping — so a refactor cannot silently drop one of the
 * seven OCG operations or rename an operation away from its registered
 * {@code OCG_*} task type.</p>
 */
class OcgAgentFactoryTest {

    private static OcgProperties props() {
        OcgProperties p = new OcgProperties();
        p.setUrl("http://ocg.local");
        p.setModel("openai/gpt-4o-mini");
        return p;
    }

    @Test
    void builtAgentCarriesNameModelAndSystemPrompt() {
        AgentConfig cfg = OcgAgentFactory.build(props());

        assertThat(cfg.getName()).isEqualTo("_ocg_agent");
        assertThat(cfg.getModel()).isEqualTo("openai/gpt-4o-mini");
        // System prompt must include the retrieval/aggregation distinction so
        // model behaviour stays aligned with the documented OCG contract.
        assertThat(cfg.getInstructions().toString())
                .contains("RETRIEVAL")
                .contains("aggregation")
                .contains("TWO-STEP");
    }

    @Test
    void exposesAllSevenOcgToolsWithMatchingToolTypes() {
        List<ToolConfig> tools = OcgAgentFactory.build(props()).getTools();
        assertThat(tools).hasSize(7);

        // Each tool's `name` must match its `toolType` so ToolCompiler's
        // TYPE_MAP lookup resolves to the right OCG_* task type. A drift here
        // would silently route the tool call to a SIMPLE task with no worker.
        for (ToolConfig t : tools) {
            assertThat(t.getName())
                    .as("tool name == toolType (so TYPE_MAP routes correctly)")
                    .isEqualTo(t.getToolType());
        }

        List<String> names = tools.stream().map(ToolConfig::getName).toList();
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "ocg_query",
                        "ocg_get_entity",
                        "ocg_neighborhood",
                        "ocg_code_history",
                        "ocg_memory_set",
                        "ocg_memory_reinforce",
                        "ocg_memory_delete");
    }

    @Test
    void systemPromptReferencesRuntimeDateInput() {
        // The date anchor for "recent" / "last week" style queries must be a
        // Conductor expression resolved per execution from the __today__
        // sub-workflow input (supplied by the agent_tool dispatch script) —
        // NOT a date baked in at boot, which drifts on a long-running server
        // until the prompt claims yesterday (or last month) is "today".
        String prompt = OcgAgentFactory.build(props()).getInstructions().toString();
        // "Today's date is <expression>" — anything else (a literal date, a
        // leftover placeholder) means the anchor is frozen at boot time.
        assertThat(prompt).contains("Today's date is ${workflow.input.__today__}");
        assertThat(prompt).doesNotContain("{{TODAY}}");
    }

    @Test
    void systemPromptTellsModelToOmitEndTimeForOpenEndedRanges() {
        // Observed failure: asked to "catch me up on the current state", the
        // model set end_time to a month boundary BEFORE today (2026-06-01
        // with today = 2026-06-11), silently dropping the most recent days.
        // The prompt must instruct: a range that extends to the present has
        // NO end_time. And it must not contain literal example dates — a
        // hardcoded month-shaped window is exactly what the model imitated.
        String prompt = OcgAgentFactory.build(props()).getInstructions().toString();
        assertThat(prompt).containsIgnoringCase("omit end_time");
        assertThat(prompt)
                .as("no hardcoded yyyy-MM-dd example dates for the model to anchor on")
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void buildFailsFastWhenModelIsBlank() {
        OcgProperties noModel = new OcgProperties();
        noModel.setUrl("http://ocg.local");
        // model deliberately unset — operator forgot OCG_MODEL.
        assertThatThrownBy(() -> OcgAgentFactory.build(noModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCG_MODEL");
    }

    @Test
    void queryToolDeclaresQueryAsRequiredInput() {
        ToolConfig queryTool = OcgAgentFactory.build(props()).getTools().stream()
                .filter(t -> "ocg_query".equals(t.getName()))
                .findFirst()
                .orElseThrow();
        Object required = queryTool.getInputSchema().get("required");
        assertThat(required).asList().contains("query");
    }
}
