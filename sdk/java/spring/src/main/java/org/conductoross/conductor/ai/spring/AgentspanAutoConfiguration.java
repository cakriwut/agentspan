package org.conductoross.conductor.ai.spring;

import org.conductoross.conductor.ai.AgentConfig;
import org.conductoross.conductor.ai.AgentRuntime;
import io.orkes.conductor.client.ApiClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AgentspanProperties.class)
public class AgentspanAutoConfiguration {

    /** The native Conductor client (server URL + auth) — the SDK's transport. */
    @Bean
    @ConditionalOnMissingBean
    public ApiClient agentspanConductorClient(AgentspanProperties props) {
        return AgentRuntime.client(props.getServerUrl(), props.getAuthKey(), props.getAuthSecret());
    }

    /** Worker-runner tuning. */
    @Bean
    @ConditionalOnMissingBean
    public AgentConfig agentspanConfig(AgentspanProperties props) {
        return new AgentConfig(props.getWorkerPollIntervalMs(), props.getWorkerThreadCount());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(ApiClient conductorClient, AgentConfig agentConfig) {
        return new AgentRuntime(conductorClient, agentConfig);
    }
}
