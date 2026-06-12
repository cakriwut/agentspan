/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.service.MetadataService;

/**
 * Generic registrar that writes every {@link RegisteredTaskDefs}-contributed
 * {@link TaskDef} into Conductor's metadata store on startup.
 *
 * <p>{@link RegisteredAgentRegistrar} declares an explicit dependency on
 * this bean via {@code @DependsOn} so task defs are written before any
 * agent workflow compiles — agent configs frequently reference task
 * names whose defs must already exist.</p>
 */
@Component
public class RegisteredTaskDefsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RegisteredTaskDefsRegistrar.class);

    // Service layer, not MetadataDAO: orkes' fork changed the DAO's updateTaskDef
    // return type (TaskDef -> String), so DAO calls compiled against OSS break at
    // runtime when embedded. MetadataService.updateTaskDef is void in both.
    private final MetadataService metadataService;
    private final List<RegisteredTaskDefs> suppliers;

    @Autowired
    public RegisteredTaskDefsRegistrar(
            MetadataService metadataService, @Autowired(required = false) List<RegisteredTaskDefs> suppliers) {
        this.metadataService = metadataService;
        this.suppliers = suppliers != null ? suppliers : List.of();
    }

    @PostConstruct
    public void registerAll() {
        if (suppliers.isEmpty()) {
            return;
        }
        // Upsert via the service layer's create/update split: hosts differ on
        // updateTaskDef semantics (OSS DAO upserts; orkes' service throws NOT_FOUND
        // for unknown names), so check existence against the full list first.
        Set<String> existing =
                metadataService.getTaskDefs().stream()
                        .map(TaskDef::getName)
                        .collect(Collectors.toSet());
        List<TaskDef> toCreate = new ArrayList<>();
        int updated = 0;
        for (RegisteredTaskDefs supplier : suppliers) {
            for (TaskDef def : supplier.taskDefs()) {
                if (existing.contains(def.getName())) {
                    metadataService.updateTaskDef(def);
                    updated++;
                } else {
                    toCreate.add(def);
                }
            }
        }
        if (!toCreate.isEmpty()) {
            metadataService.registerTaskDef(toCreate);
        }
        int count = updated + toCreate.size();
        if (count > 0) {
            log.info("Registered {} TaskDef(s) from {} supplier(s)", count, suppliers.size());
        }
    }
}
