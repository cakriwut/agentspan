/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the seven {@code OCG_*} system tasks as Conductor beans when
 * {@code agentspan.ocg.url} is set. Bean names match the Conductor task type
 * strings so the framework's {@code SystemTaskRegistry} looks them up by type.
 */
@Configuration
@EnableConfigurationProperties(OcgProperties.class)
@ConditionalOnProperty(prefix = "agentspan.ocg", name = "url")
public class OcgRequestTaskConfig {

    public static final String TASK_TYPE_QUERY = "OCG_QUERY";
    public static final String TASK_TYPE_GET_ENTITY = "OCG_GET_ENTITY";
    public static final String TASK_TYPE_NEIGHBORHOOD = "OCG_NEIGHBORHOOD";
    public static final String TASK_TYPE_CODE_HISTORY = "OCG_CODE_HISTORY";
    public static final String TASK_TYPE_MEMORY_SET = "OCG_MEMORY_SET";
    public static final String TASK_TYPE_MEMORY_REINFORCE = "OCG_MEMORY_REINFORCE";
    public static final String TASK_TYPE_MEMORY_DELETE = "OCG_MEMORY_DELETE";

    @Bean(TASK_TYPE_QUERY)
    public OcgRequestTask ocgQueryTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_QUERY, OcgRequestTask.OP_QUERY, props);
    }

    @Bean(TASK_TYPE_GET_ENTITY)
    public OcgRequestTask ocgGetEntityTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_GET_ENTITY, OcgRequestTask.OP_GET_ENTITY, props);
    }

    @Bean(TASK_TYPE_NEIGHBORHOOD)
    public OcgRequestTask ocgNeighborhoodTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_NEIGHBORHOOD, OcgRequestTask.OP_NEIGHBORHOOD, props);
    }

    @Bean(TASK_TYPE_CODE_HISTORY)
    public OcgRequestTask ocgCodeHistoryTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_CODE_HISTORY, OcgRequestTask.OP_CODE_HISTORY, props);
    }

    @Bean(TASK_TYPE_MEMORY_SET)
    public OcgRequestTask ocgMemorySetTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_MEMORY_SET, OcgRequestTask.OP_MEMORY_SET, props);
    }

    @Bean(TASK_TYPE_MEMORY_REINFORCE)
    public OcgRequestTask ocgMemoryReinforceTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_MEMORY_REINFORCE, OcgRequestTask.OP_MEMORY_REINFORCE, props);
    }

    @Bean(TASK_TYPE_MEMORY_DELETE)
    public OcgRequestTask ocgMemoryDeleteTask(OcgProperties props) {
        return new OcgRequestTask(TASK_TYPE_MEMORY_DELETE, OcgRequestTask.OP_MEMORY_DELETE, props);
    }
}
