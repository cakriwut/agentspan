/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

class OcgToolValidatorTest {

    private static OcgProperties enabled() {
        return new OcgProperties();
    }

    private static ToolConfig ocgTool(Map<String, Object> config) {
        return ToolConfig.builder()
                .name("ocg_query")
                .toolType("ocg_query")
                .description("Query OCG")
                .config(config)
                .build();
    }

    private static AgentConfig agentWith(ToolConfig... tools) {
        return AgentConfig.builder()
                .name("main")
                .model("openai/gpt-4o")
                .tools(List.of(tools))
                .build();
    }

    @Test
    void ocgToolWithPerToolUrlIsValid() {
        AgentConfig config = agentWith(ocgTool(Map.of("url", "https://us.ocg.example.com")));

        assertThat(OcgToolValidator.validate(config, enabled())).isEmpty();
    }

    @Test
    void ocgToolWithoutUrlIsRejected() {
        // No server-side default instance exists — every OCG tool must bind
        // its own url.
        AgentConfig config = agentWith(ocgTool(null));

        Optional<String> error = OcgToolValidator.validate(config, enabled());

        assertThat(error).isPresent();
        assertThat(error.get()).contains("ocg_query").contains("url=");
    }

    @Test
    void ocgToolIsRejectedWhenFeatureDisabled() {
        AgentConfig config = agentWith(ocgTool(Map.of("url", "https://us.ocg.example.com")));

        Optional<String> error = OcgToolValidator.validate(config, null);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("agentspan.ocg.enabled");
    }

    @Test
    void nonOcgToolsAreIgnored() {
        AgentConfig config = agentWith(ToolConfig.builder()
                .name("fetch")
                .toolType("http")
                .config(Map.of("url", "https://example.com"))
                .build());

        assertThat(OcgToolValidator.validate(config, null)).isEmpty();
    }

    @Test
    void instancelessOcgToolInsideInlineAgentToolChildIsRejected() {
        // The SDK serializes agent_tool children as raw maps under
        // config.agentConfig — the validator must walk that shape too.
        Map<String, Object> childAgent = Map.of(
                "name",
                "retriever",
                "tools",
                List.of(Map.of(
                        "name", "ocg_query",
                        "toolType", "ocg_query")));
        ToolConfig agentTool = ToolConfig.builder()
                .name("retriever")
                .toolType("agent_tool")
                .config(Map.of("agentConfig", childAgent))
                .build();

        Optional<String> error = OcgToolValidator.validate(agentWith(agentTool), enabled());

        assertThat(error).isPresent();
        assertThat(error.get()).contains("ocg_query");
    }

    @Test
    void instancelessOcgToolInSubAgentIsRejected() {
        AgentConfig sub = agentWith(ocgTool(null));
        AgentConfig main = AgentConfig.builder()
                .name("main")
                .model("openai/gpt-4o")
                .agents(List.of(sub))
                .build();

        assertThat(OcgToolValidator.validate(main, enabled())).isPresent();
    }
}
