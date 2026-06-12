/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.tasks.TaskDef;

import dev.agentspan.runtime.ocg.operation.OcgCodeHistoryOperation;
import dev.agentspan.runtime.ocg.operation.OcgGetEntityOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryDeleteOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryReinforceOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemorySetOperation;
import dev.agentspan.runtime.ocg.operation.OcgNeighborhoodOperation;
import dev.agentspan.runtime.ocg.operation.OcgQueryOperation;
import dev.agentspan.runtime.registry.RegisteredTaskDefs;

/**
 * OCG's contribution to the {@link RegisteredTaskDefs} registry.
 *
 * <p>Conductor resolves dynamic-fork tasks by name; the seven
 * {@code ocg_*} TaskDefs registered here let the OCG sub-agent's tool
 * calls dispatch successfully at runtime. {@code retryCount = 0} on
 * purpose — each call is a stateless HTTP round-trip handled by
 * {@code OcgRequestTask} and the parent LLM loop owns retry decisions.</p>
 */
@Component
@ConditionalOnProperty(prefix = "agentspan.ocg", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OcgRegisteredTaskDefs implements RegisteredTaskDefs {

    private static final int OCG_TASK_TIMEOUT_SECONDS = 60;
    private static final int OCG_TASK_RETRY_COUNT = 0;
    private static final String OCG_TASK_OWNER_EMAIL = "ocg@agentspan.dev";

    /**
     * TaskDef names must equal {@code taskType.toLowerCase()} — that is the
     * name the tool-dispatch script assigns to each scheduled OCG task, and
     * Conductor resolves dynamic-fork tasks by name. The operation NAME
     * constants ("query", "memory_set", …) are log/output labels, not task
     * names — registering those leaves "ocg_query" unresolvable at dispatch.
     */
    private static final List<String> TASK_NAMES = List.of(
            OcgQueryOperation.TASK_TYPE.toLowerCase(),
            OcgGetEntityOperation.TASK_TYPE.toLowerCase(),
            OcgNeighborhoodOperation.TASK_TYPE.toLowerCase(),
            OcgCodeHistoryOperation.TASK_TYPE.toLowerCase(),
            OcgMemorySetOperation.TASK_TYPE.toLowerCase(),
            OcgMemoryReinforceOperation.TASK_TYPE.toLowerCase(),
            OcgMemoryDeleteOperation.TASK_TYPE.toLowerCase());

    @Override
    public List<TaskDef> taskDefs() {
        return TASK_NAMES.stream().map(OcgRegisteredTaskDefs::buildTaskDef).toList();
    }

    private static TaskDef buildTaskDef(String name) {
        TaskDef def = new TaskDef();
        def.setName(name);
        def.setRetryCount(OCG_TASK_RETRY_COUNT);
        def.setTimeoutSeconds(OCG_TASK_TIMEOUT_SECONDS);
        def.setResponseTimeoutSeconds(OCG_TASK_TIMEOUT_SECONDS);
        def.setOwnerEmail(OCG_TASK_OWNER_EMAIL);
        return def;
    }
}
