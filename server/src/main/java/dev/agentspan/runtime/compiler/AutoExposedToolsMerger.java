/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.dao.MetadataDAO;

import dev.agentspan.runtime.model.AgentConfig;
import dev.agentspan.runtime.model.ToolConfig;

/**
 * Owns the "auto-expose registered sub-agents as tools" contract for
 * {@link AgentCompiler}.
 *
 * <p>The contract: any workflow registered in Conductor's metadata store
 * carrying the {@link #AUTO_EXPOSE_AS_TOOL_METADATA_KEY} marker is silently
 * appended to every top-level agent's tool list as an {@code agent_tool}
 * at compile time. The OCG sub-agent (and any future server-registered
 * sub-agent) becomes LLM-visible via this single mechanism — no per-feature
 * injection class required.</p>
 *
 * <p>Lifecycle: the result of the DAO scan is cached on first successful
 * read and never refreshed. Registered server-side agents are written at
 * server {@code @PostConstruct} time and don't change at runtime, so
 * re-querying the DAO per compile would be wasted work. A transient DAO
 * failure is <em>not</em> cached — the next compile retries the lookup so
 * a one-shot blip doesn't permanently hide registered agents.</p>
 *
 * <p>Bootstrap ordering: the registrar that writes auto-exposed agents
 * must call {@link AgentCompiler#compileWithoutAutoExpose} during its
 * {@code @PostConstruct} loop, never {@link AgentCompiler#compile}.
 * Triggering the merge before the registrar finishes its
 * {@code dao.updateWorkflowDef} writes would snapshot an empty list and
 * freeze it for the bean's lifetime.</p>
 */
@Component
public class AutoExposedToolsMerger {

    /**
     * Metadata key on a {@link WorkflowDef} that marks the workflow as one
     * that should be silently appended to every top-level agent's tool list
     * at compile time. The value is a {@code Map<String,Object>} with at
     * least {@code name} and {@code description}; both are surfaced to the
     * agent's LLM via the {@code agent_tool} routing.
     */
    public static final String AUTO_EXPOSE_AS_TOOL_METADATA_KEY = "agentspan.autoExposeAsTool";

    private static final Logger log = LoggerFactory.getLogger(AutoExposedToolsMerger.class);

    /**
     * Optional — null for unit tests that construct {@link AgentCompiler}
     * directly. When null, {@link #merge(AgentConfig)} is a no-op.
     */
    private final MetadataDAO metadataDAO;

    /**
     * Lazy cache of the auto-exposed tool entries the DAO returns. Populated
     * on first successful {@link #autoExposedEntries()} call and never
     * refreshed. {@code volatile} for the double-checked-locking idiom.
     */
    private volatile List<AutoExposedEntry> cachedAutoExposed;

    @Autowired
    public AutoExposedToolsMerger(@Autowired(required = false) MetadataDAO metadataDAO) {
        this.metadataDAO = metadataDAO;
    }

    /** A no-DAO merger that is always a no-op. For tests / direct construction. */
    public static AutoExposedToolsMerger disabled() {
        return new AutoExposedToolsMerger(null);
    }

    /**
     * Append every DAO-registered workflow that carries the
     * {@link #AUTO_EXPOSE_AS_TOOL_METADATA_KEY} marker to {@code config.tools}
     * as an {@code agent_tool}.
     *
     * <p>Mutates {@code config} in place. Skips when:</p>
     * <ul>
     *   <li>{@link #metadataDAO} is absent (unit-test path)</li>
     *   <li>The workflow being compiled IS the auto-exposed one
     *       — no self-recursion</li>
     *   <li>A tool with that name is already declared on the config
     *       — caller's explicit declaration wins</li>
     * </ul>
     */
    public void merge(AgentConfig config) {
        if (config == null) return;
        List<AutoExposedEntry> entries = autoExposedEntries();
        if (entries.isEmpty()) return;

        Set<String> takenNames = collectToolNames(config);
        List<ToolConfig> toAppend = new ArrayList<>();
        for (AutoExposedEntry entry : entries) {
            if (entry.workflowName().equals(config.getName())) continue; // self-recursion guard
            if (!takenNames.add(entry.tool().getName())) continue; // caller's declaration wins
            toAppend.add(entry.tool());
            log.info(
                    "Auto-exposed workflow '{}' as agent_tool '{}' on '{}'",
                    entry.workflowName(),
                    entry.tool().getName(),
                    config.getName());
        }
        if (!toAppend.isEmpty()) {
            appendTools(config, toAppend);
        }
    }

    /** Typed view of an {@link #AUTO_EXPOSE_AS_TOOL_METADATA_KEY} entry. */
    private record AutoExposeSpec(String toolName, String description) {}

    /**
     * Cached pairing of source workflow name + pre-built {@code agent_tool}
     * {@link ToolConfig}. The workflow name rides alongside the tool so the
     * per-compile self-recursion guard stays correct without re-reading
     * {@link WorkflowDef} metadata.
     */
    private record AutoExposedEntry(String workflowName, ToolConfig tool) {}

    private List<AutoExposedEntry> autoExposedEntries() {
        List<AutoExposedEntry> snapshot = cachedAutoExposed;
        if (snapshot != null) return snapshot;
        if (metadataDAO == null) {
            cachedAutoExposed = List.of();
            return cachedAutoExposed;
        }
        synchronized (this) {
            if (cachedAutoExposed != null) return cachedAutoExposed;
            try {
                List<WorkflowDef> defs = metadataDAO.getAllWorkflowDefsLatestVersions();
                List<AutoExposedEntry> built = buildEntries(defs == null ? List.of() : defs);
                cachedAutoExposed = built;
                log.debug(
                        "auto-expose merge: fetched {} workflow def(s); cached {} auto-exposed entry(ies)",
                        defs == null ? 0 : defs.size(),
                        built.size());
                return built;
            } catch (Exception e) {
                // NOT cached — let the next compile retry the DAO.
                log.warn(
                        "auto-expose merge: metadataDAO lookup failed; will retry on next compile. {}",
                        e.getMessage());
                return List.of();
            }
        }
    }

    private static List<AutoExposedEntry> buildEntries(List<WorkflowDef> defs) {
        List<AutoExposedEntry> built = new ArrayList<>();
        for (WorkflowDef def : defs) {
            AutoExposeSpec spec = readAutoExposeSpec(def);
            if (spec == null) continue;
            built.add(new AutoExposedEntry(def.getName(), buildAgentTool(def.getName(), spec)));
        }
        return built;
    }

    private static Set<String> collectToolNames(AgentConfig config) {
        if (config.getTools() == null) return new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ToolConfig t : config.getTools()) {
            if (t.getName() != null) names.add(t.getName());
        }
        return names;
    }

    private static AutoExposeSpec readAutoExposeSpec(WorkflowDef def) {
        Map<String, Object> metadata = def.getMetadata();
        if (metadata == null || !(metadata.get(AUTO_EXPOSE_AS_TOOL_METADATA_KEY) instanceof Map<?, ?> spec)) {
            return null;
        }
        if (!(spec.get("name") instanceof String toolName) || toolName.isEmpty()) return null;
        String description = spec.get("description") instanceof String s ? s : "";
        return new AutoExposeSpec(toolName, description);
    }

    private static ToolConfig buildAgentTool(String workflowName, AutoExposeSpec spec) {
        return ToolConfig.builder()
                .name(spec.toolName())
                .toolType("agent_tool")
                .description(spec.description())
                .config(Map.of("workflowName", workflowName))
                .build();
    }

    private static void appendTools(AgentConfig config, List<ToolConfig> toAppend) {
        List<ToolConfig> merged = new ArrayList<>(config.getTools() != null ? config.getTools() : List.of());
        merged.addAll(toAppend);
        config.setTools(merged);
    }
}
