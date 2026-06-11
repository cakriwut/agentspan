# Spring Boot

The `conductor-ai-sdk-spring` module provides Spring Boot auto-configuration. Add it and your `AgentRuntime` is wired automatically from `application.properties`.

## Dependency

=== "Gradle"

    ```groovy
    implementation 'org.conductoross.conductor:conductor-ai-sdk-spring:0.1.0'
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>org.conductoross.conductor</groupId>
        <artifactId>conductor-ai-sdk-spring</artifactId>
        <version>0.1.0</version>
    </dependency>
    ```

This pulls in both `conductor-ai-sdk` and `conductor-client-spring` (which wires the `ApiClient`).

## Configuration

```properties
# application.properties

# Conductor server — from conductor-client-spring
conductor.root-uri=http://localhost:6767/api
conductor.security.client.key-id=your-key      # optional
conductor.security.client.secret=your-secret   # optional

# Agentspan worker tuning
agentspan.worker-poll-interval-ms=100
agentspan.worker-thread-count=1
```

## Inject and use

```java
import org.conductoross.conductor.ai.AgentRuntime;
import org.conductoross.conductor.ai.Agent;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final AgentRuntime runtime;

    public ChatService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    public String answer(String question) {
        Agent agent = Agent.builder()
            .name("assistant")
            .model("openai/gpt-4o-mini")
            .instructions("You are a helpful assistant.")
            .build();

        return runtime.run(agent, question).getOutput();
    }
}
```

## Beans provided

| Bean type | Bean name | Condition |
|---|---|---|
| `ApiClient` | `orkesConductorClient` | From `conductor-client-spring`; `@ConditionalOnMissingBean` |
| `AgentConfig` | `agentspanConfig` | From `agentspan.*` properties; `@ConditionalOnMissingBean` |
| `AgentRuntime` | `agentRuntime` | Wires `ApiClient` + `AgentConfig`; `@ConditionalOnMissingBean` |

All beans are `@ConditionalOnMissingBean` — declare your own to override any of them.

## Override the ApiClient

To connect to multiple servers or use custom TLS:

```java
@Configuration
public class MyAgentspanConfig {

    @Bean
    public ApiClient agentspanClient() {
        return AgentRuntime.client("http://myserver:6767", "key", "secret");
    }
}
```

## Override AgentConfig

```java
@Bean
public AgentConfig agentspanConfig() {
    return new AgentConfig(500, 4);   // 500ms poll, 4 worker threads
}
```

## Graceful shutdown

`AgentRuntime` is `AutoCloseable`. Spring calls `close()` on context shutdown automatically when it is a Spring-managed bean — worker threads stop and HTTP connections are released cleanly.
