/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.agentspan.runtime.ocg.operation.OcgCodeHistoryOperation;
import dev.agentspan.runtime.ocg.operation.OcgGetEntityOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryDeleteOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryReinforceOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemorySetOperation;
import dev.agentspan.runtime.ocg.operation.OcgNeighborhoodOperation;
import dev.agentspan.runtime.ocg.operation.OcgQueryOperation;

/**
 * Registers one {@link OcgRequestTask} bean per OCG operation when
 * {@code agentspan.ocg.url} is set. Bean names match the Conductor task
 * type strings so {@code SystemTaskRegistry} looks them up by type at
 * dispatch time.
 *
 * <p>Each {@code @Bean} method pairs a fresh {@code OcgRequestTask} with
 * a stateless operation strategy — the strategies own the per-endpoint
 * URL/method/body/projection details; {@code OcgRequestTask} only knows
 * how to send and shape the result.</p>
 */
@Configuration
@EnableConfigurationProperties(OcgProperties.class)
// Empty agentspan.ocg.url means "feature off" — must use an expression
// because @ConditionalOnProperty matches empty strings.
@ConditionalOnExpression("'${agentspan.ocg.url:}'.length() > 0")
public class OcgRequestTaskConfig {

    @Bean(OcgQueryOperation.TASK_TYPE)
    public OcgRequestTask ocgQueryTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgQueryOperation(), properties);
    }

    @Bean(OcgGetEntityOperation.TASK_TYPE)
    public OcgRequestTask ocgGetEntityTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgGetEntityOperation(), properties);
    }

    @Bean(OcgNeighborhoodOperation.TASK_TYPE)
    public OcgRequestTask ocgNeighborhoodTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgNeighborhoodOperation(), properties);
    }

    @Bean(OcgCodeHistoryOperation.TASK_TYPE)
    public OcgRequestTask ocgCodeHistoryTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgCodeHistoryOperation(), properties);
    }

    @Bean(OcgMemorySetOperation.TASK_TYPE)
    public OcgRequestTask ocgMemorySetTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgMemorySetOperation(), properties);
    }

    @Bean(OcgMemoryReinforceOperation.TASK_TYPE)
    public OcgRequestTask ocgMemoryReinforceTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgMemoryReinforceOperation(), properties);
    }

    @Bean(OcgMemoryDeleteOperation.TASK_TYPE)
    public OcgRequestTask ocgMemoryDeleteTask(OcgProperties properties) {
        return new OcgRequestTask(new OcgMemoryDeleteOperation(), properties);
    }
}
