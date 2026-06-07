# Agentspan Java SDK

Build durable AI agents in Java, backed by [Conductor](https://conductor.netflix.com/) workflows. Your agents survive process crashes, tool calls scale independently, and human approvals can take days — all without managing state yourself.

## Installation

=== "Gradle"

    ```groovy
    implementation 'org.conductoross.conductor:conductor-ai-sdk:0.1.0'
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>org.conductoross.conductor</groupId>
        <artifactId>conductor-ai-sdk</artifactId>
        <version>0.1.0</version>
    </dependency>
    ```

**Requirements:** Java 21+ · Agentspan server (see [self-hosting](../self-hosting.md))

## Hello World

```java
import org.conductoross.conductor.ai.Agent;
import org.conductoross.conductor.ai.AgentRuntime;
import org.conductoross.conductor.ai.model.AgentResult;

Agent agent = Agent.builder()
    .name("assistant")
    .model("openai/gpt-4o-mini")
    .instructions("You are a helpful assistant.")
    .build();

try (AgentRuntime runtime = new AgentRuntime()) {
    AgentResult result = runtime.run(agent, "What is the capital of France?");
    System.out.println(result.getOutput());
}
```

## What makes it different

| Feature | Agentspan | Thread-based SDKs |
|---|---|---|
| Survives crashes | ✅ Conductor workflow | ❌ State lost |
| Tool workers | ✅ Distributed tasks | ❌ In-process only |
| Long-running | ✅ Days / weeks | ❌ Minutes |
| Human-in-the-loop | ✅ Native approval flow | ❌ Polling hacks |
| Observability | ✅ Full workflow audit log | ❌ Log scraping |

## Next steps

- [Getting Started](getting-started.md) — install, configure, and run your first agent
- [Core Concepts → Agents](concepts/agents.md) — the full `Agent.builder()` API
- [Core Concepts → Tools](concepts/tools.md) — Java methods as Conductor worker tasks
- [API Reference](api-reference.md) — complete method signatures
