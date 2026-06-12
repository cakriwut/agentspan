/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import java.util.List;

import com.netflix.conductor.common.metadata.tasks.TaskDef;

/**
 * Marker for a Spring bean that contributes Conductor {@link TaskDef}
 * entries to the metadata store at startup.
 *
 * <p>Conductor's dynamic-fork dispatcher resolves tasks <em>by name</em>
 * via the TaskDef registry. Custom system task types
 * therefore need a matching TaskDef registered before any workflow can
 * dispatch them. Beans of this type plug into
 * {@link RegisteredTaskDefsRegistrar} and the registration happens
 * generically — no per-feature {@code @PostConstruct}.</p>
 */
public interface RegisteredTaskDefs {

    /** Task definitions this bean wants registered. */
    List<TaskDef> taskDefs();
}
