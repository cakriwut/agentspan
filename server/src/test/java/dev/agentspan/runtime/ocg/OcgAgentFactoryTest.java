/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
    void systemPromptHasTodayUtcDateSubstituted() {
        // The {{TODAY}} placeholder is the anchor for "recent" / "last week"
        // style queries. If a refactor silently drops the .replace() call, the
        // LLM gets a literal "{{TODAY}}" and starts inventing dates again —
        // exactly the failure mode the placeholder was added to prevent.
        String prompt = OcgAgentFactory.build(props()).getInstructions().toString();
        assertThat(prompt).contains(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(prompt).doesNotContain("{{TODAY}}");
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
