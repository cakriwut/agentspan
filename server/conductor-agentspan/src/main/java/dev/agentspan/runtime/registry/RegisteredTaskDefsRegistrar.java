/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.registry;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.dao.MetadataDAO;

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

    private final MetadataDAO metadataDAO;
    private final List<RegisteredTaskDefs> suppliers;

    @Autowired
    public RegisteredTaskDefsRegistrar(
            MetadataDAO metadataDAO, @Autowired(required = false) List<RegisteredTaskDefs> suppliers) {
        this.metadataDAO = metadataDAO;
        this.suppliers = suppliers != null ? suppliers : List.of();
    }

    @PostConstruct
    public void registerAll() {
        int count = 0;
        for (RegisteredTaskDefs supplier : suppliers) {
            for (TaskDef def : supplier.taskDefs()) {
                metadataDAO.updateTaskDef(def);
                count++;
            }
        }
        if (count > 0) {
            log.info("Registered {} TaskDef(s) from {} supplier(s)", count, suppliers.size());
        }
    }
}
