/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.agentspan.runtime.compiler.ToolCompiler;
import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Start-time fail-fast for OCG tools: an OCG tool without a bound
 * instance ({@code url} in its config) would otherwise surface as a task
 * failure mid-conversation. Checked
 * recursively — sub-agents and inline {@code agent_tool} children (which
 * arrive as raw maps from the SDK serializer) carry OCG tools too.
 */
public final class OcgToolValidator {

    private OcgToolValidator() {}

    /**
     * @param config     the agent about to start (recursed into)
     * @param properties the server's OCG properties, or {@code null} when the
     *                   OCG feature is disabled ({@code agentspan.ocg.enabled=false})
     * @return an error message when the start request must be rejected
     */
    public static Optional<String> validate(AgentConfig config, OcgProperties properties) {
        if (config == null) {
            return Optional.empty();
        }
        boolean featureEnabled = properties != null;

        Optional<String> error = validateTools(config.getTools(), featureEnabled);
        if (error.isPresent()) {
            return error;
        }
        if (config.getAgents() != null) {
            for (AgentConfig sub : config.getAgents()) {
                error = validate(sub, properties);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateTools(List<ToolConfig> tools, boolean featureEnabled) {
        if (tools == null) {
            return Optional.empty();
        }
        for (ToolConfig tool : tools) {
            String toolType = tool.getToolType();
            Map<String, Object> cfg = tool.getConfig();

            if (toolType != null && ToolCompiler.OCG_TOOL_TYPES.contains(toolType)) {
                Optional<String> error = checkOcgTool(tool.getName(), cfg, featureEnabled);
                if (error.isPresent()) {
                    return error;
                }
            } else if ("agent_tool".equals(toolType) && cfg != null) {
                Optional<String> error = validateChild(cfg.get("agentConfig"), featureEnabled);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Inline {@code agent_tool} children arrive either as typed
     * {@link AgentConfig} (built server-side) or as the raw map the SDK
     * serializer produced — both shapes are walked.
     */
    @SuppressWarnings("unchecked")
    private static Optional<String> validateChild(Object child, boolean featureEnabled) {
        if (child instanceof AgentConfig typed) {
            return validate(typed, featureEnabled ? new OcgProperties() : null);
        }
        if (!(child instanceof Map<?, ?> childMap)) {
            return Optional.empty();
        }

        Object tools = childMap.get("tools");
        if (tools instanceof List<?> toolList) {
            for (Object t : toolList) {
                if (!(t instanceof Map<?, ?> toolMap)) {
                    continue;
                }
                String toolType = asString(toolMap.get("toolType"));
                Map<String, Object> cfg = toolMap.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                if (toolType != null && ToolCompiler.OCG_TOOL_TYPES.contains(toolType)) {
                    Optional<String> error = checkOcgTool(asString(toolMap.get("name")), cfg, featureEnabled);
                    if (error.isPresent()) {
                        return error;
                    }
                } else if ("agent_tool".equals(toolType) && cfg != null) {
                    Optional<String> error = validateChild(cfg.get("agentConfig"), featureEnabled);
                    if (error.isPresent()) {
                        return error;
                    }
                }
            }
        }
        Object agents = childMap.get("agents");
        if (agents instanceof List<?> agentList) {
            for (Object sub : agentList) {
                Optional<String> error = validateChild(sub, featureEnabled);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkOcgTool(String name, Map<String, Object> cfg, boolean featureEnabled) {
        String toolName = name != null ? name : "(unnamed)";
        if (!featureEnabled) {
            return Optional.of("OCG tool '" + toolName + "' cannot run: OCG is disabled on this server "
                    + "(agentspan.ocg.enabled=false). Remove the OCG tools or enable OCG.");
        }
        String url = cfg != null ? asString(cfg.get("url")) : null;
        if (url == null || url.isBlank()) {
            return Optional.of("OCG tool '" + toolName + "' has no OCG instance bound: set url= on "
                    + "ocg_agent()/ocg_tools() in the SDK.");
        }
        return Optional.empty();
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
