/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.agentspan.runtime.compiler.ToolCompiler;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Integration test pinning the OCG tool type → Conductor task type contract
 * between {@link OcgAgentFactory} and {@link ToolCompiler}.
 *
 * <p>If anyone ever renames an OCG tool type without updating ToolCompiler's
 * TYPE_MAP, the LLM tool call would fall through to a SIMPLE task with no
 * worker and the workflow would hang forever. This test catches that drift
 * at compile time on the test surface.</p>
 */
class OcgToolCompilerIntegrationTest {

    @Test
    void everyOcgToolSpecCompilesToItsRegisteredSystemTaskType() {
        ToolCompiler compiler = new ToolCompiler();
        List<ToolConfig> tools = OcgAgentFactory.buildTools();
        List<Map<String, Object>> specs = compiler.compileToolSpecs(tools);

        Map<String, String> expectedTaskTypes = Map.of(
                "ocg_query", "OCG_QUERY",
                "ocg_get_entity", "OCG_GET_ENTITY",
                "ocg_neighborhood", "OCG_NEIGHBORHOOD",
                "ocg_code_history", "OCG_CODE_HISTORY",
                "ocg_memory_set", "OCG_MEMORY_SET",
                "ocg_memory_reinforce", "OCG_MEMORY_REINFORCE",
                "ocg_memory_delete", "OCG_MEMORY_DELETE");

        for (Map<String, Object> spec : specs) {
            String name = (String) spec.get("name");
            String conductorType = (String) spec.get("type");
            assertThat(conductorType)
                    .as("OCG tool '%s' must compile to its registered task type", name)
                    .isEqualTo(expectedTaskTypes.get(name));
        }
    }
}
