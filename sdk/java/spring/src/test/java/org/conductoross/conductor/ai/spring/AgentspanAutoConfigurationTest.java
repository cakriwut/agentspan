package org.conductoross.conductor.ai.spring;

import org.conductoross.conductor.ai.AgentConfig;
import org.conductoross.conductor.ai.AgentRuntime;
import io.orkes.conductor.client.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class AgentspanAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentspanAutoConfiguration.class));

    @Test
    void registersClientConfigAndRuntimeBeansWithDefaults() {
        runner.run(ctx -> {
            assertTrue(ctx.containsBean("agentspanConductorClient"));
            assertTrue(ctx.containsBean("agentspanConfig"));
            assertTrue(ctx.containsBean("agentRuntime"));

            // Connection lives on the Conductor client (base path includes /api).
            ApiClient client = ctx.getBean(ApiClient.class);
            assertEquals("http://localhost:6767/api", client.getBasePath());

            // Worker tuning lives on AgentConfig.
            AgentConfig config = ctx.getBean(AgentConfig.class);
            assertEquals(100, config.getWorkerPollIntervalMs());
            assertEquals(1, config.getWorkerThreadCount());
        });
    }

    @Test
    void respectsCustomProperties() {
        runner
            .withPropertyValues(
                "agentspan.server-url=http://myserver:9090",
                "agentspan.auth-key=mykey",
                "agentspan.auth-secret=mysecret",
                "agentspan.worker-thread-count=4",
                "agentspan.worker-poll-interval-ms=250"
            )
            .run(ctx -> {
                ApiClient client = ctx.getBean(ApiClient.class);
                assertEquals("http://myserver:9090/api", client.getBasePath());

                AgentConfig config = ctx.getBean(AgentConfig.class);
                assertEquals(4, config.getWorkerThreadCount());
                assertEquals(250, config.getWorkerPollIntervalMs());
            });
    }

    @Test
    void doesNotOverrideUserDefinedAgentConfigBean() {
        AgentConfig custom = new AgentConfig(50, 2);
        runner
            .withBean(AgentConfig.class, () -> custom)
            .run(ctx -> {
                AgentConfig config = ctx.getBean(AgentConfig.class);
                assertSame(custom, config);
                assertEquals(50, config.getWorkerPollIntervalMs());
            });
    }

    @Test
    void doesNotOverrideUserDefinedAgentRuntimeBean() {
        AgentRuntime customRuntime = new AgentRuntime(AgentRuntime.client("http://localhost:6767"), new AgentConfig());
        runner
            .withBean(AgentRuntime.class, () -> customRuntime)
            .run(ctx -> {
                AgentRuntime runtime = ctx.getBean(AgentRuntime.class);
                assertSame(customRuntime, runtime);
                customRuntime.shutdown();
            });
    }
}
