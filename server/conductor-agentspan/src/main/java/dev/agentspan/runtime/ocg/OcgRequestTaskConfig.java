/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.agentspan.runtime.credentials.CredentialResolutionService;
import dev.agentspan.runtime.credentials.ExecutionTokenService;
import dev.agentspan.runtime.ocg.operation.OcgCodeHistoryOperation;
import dev.agentspan.runtime.ocg.operation.OcgGetEntityOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryDeleteOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryReinforceOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemorySetOperation;
import dev.agentspan.runtime.ocg.operation.OcgNeighborhoodOperation;
import dev.agentspan.runtime.ocg.operation.OcgQueryOperation;

/**
 * Registers one {@link OcgRequestTask} bean per OCG operation. Bean names
 * match the Conductor task type strings so {@code SystemTaskRegistry} looks
 * them up by type at dispatch time.
 *
 * <p>Gated on {@code agentspan.ocg.enabled} (default {@code true}) rather
 * than on a global URL: OCG instances are bound per-tool from the SDK
 * ({@code url=} + {@code credential=}), so the tasks must exist even when
 * no server-wide default instance is configured. A task dispatched with
 * neither a per-tool URL nor {@code agentspan.ocg.url} fails with a
 * pointer to both knobs.</p>
 */
@Configuration
@EnableConfigurationProperties(OcgProperties.class)
@ConditionalOnProperty(prefix = "agentspan.ocg", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OcgRequestTaskConfig {

    /**
     * Placeholder resolution for per-tool {@code #{NAME}} auth values
     * (standalone mode). When the credential services are absent (embedded
     * hosts resolve {@code ${workflow.secrets.NAME}} themselves before the
     * task starts), unresolved placeholders fail the task instead.
     */
    @Bean
    public OcgCredentialResolver ocgCredentialResolver(
            ObjectProvider<ExecutionTokenService> tokenService,
            ObjectProvider<CredentialResolutionService> resolutionService) {
        ExecutionTokenService tokens = tokenService.getIfAvailable();
        CredentialResolutionService credentials = resolutionService.getIfAvailable();
        if (tokens == null || credentials == null) {
            return (value, ctx) -> null;
        }
        return new PlaceholderCredentialResolver(tokens, credentials);
    }

    @Bean(OcgQueryOperation.TASK_TYPE)
    public OcgRequestTask ocgQueryTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgQueryOperation(), properties, resolver);
    }

    @Bean(OcgGetEntityOperation.TASK_TYPE)
    public OcgRequestTask ocgGetEntityTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgGetEntityOperation(), properties, resolver);
    }

    @Bean(OcgNeighborhoodOperation.TASK_TYPE)
    public OcgRequestTask ocgNeighborhoodTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgNeighborhoodOperation(), properties, resolver);
    }

    @Bean(OcgCodeHistoryOperation.TASK_TYPE)
    public OcgRequestTask ocgCodeHistoryTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgCodeHistoryOperation(), properties, resolver);
    }

    @Bean(OcgMemorySetOperation.TASK_TYPE)
    public OcgRequestTask ocgMemorySetTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgMemorySetOperation(), properties, resolver);
    }

    @Bean(OcgMemoryReinforceOperation.TASK_TYPE)
    public OcgRequestTask ocgMemoryReinforceTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgMemoryReinforceOperation(), properties, resolver);
    }

    @Bean(OcgMemoryDeleteOperation.TASK_TYPE)
    public OcgRequestTask ocgMemoryDeleteTask(OcgProperties properties, OcgCredentialResolver resolver) {
        return new OcgRequestTask(new OcgMemoryDeleteOperation(), properties, resolver);
    }
}
