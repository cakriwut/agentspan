# Agents

`Agent` is the single orchestration primitive. One agent wraps an LLM plus tools. An agent whose tools include other agents is a multi-agent system — no separate Team or Swarm classes.

## Builder

```java
Agent agent = Agent.builder()
    .name("my_agent")                          // required; becomes the Conductor workflow name
    .model("openai/gpt-4o-mini")               // required; "provider/model" format
    .instructions("You are a helpful agent.")  // system prompt
    .build();
```

Every field below is optional.

### Identity

| Builder method | Type | Default | Description |
|---|---|---|---|
| `name(String)` | `String` | — | **Required.** Unique workflow name. Use `snake_case`. |
| `model(String)` | `String` | — | **Required.** `"provider/model"`, e.g. `"openai/gpt-4o"`, `"anthropic/claude-opus-4-8"`. |
| `instructions(String)` | `String` | `""` | System prompt. |
| `instructionsTemplate(PromptTemplate)` | `PromptTemplate` | `null` | Prompt stored on the server; use `PromptTemplate.of("name")`. |
| `introduction(String)` | `String` | `null` | Injected before the first user message (not the system prompt). |
| `metadata(Map<String,Object>)` | `Map` | `{}` | Arbitrary key-value metadata stored with the workflow. |

### LLM tuning

| Builder method | Type | Default | Description |
|---|---|---|---|
| `maxTurns(int)` | `int` | `25` | Maximum agent loop iterations before termination. |
| `maxTokens(int)` | `int` | `null` | LLM `max_tokens`. |
| `temperature(double)` | `double` | `null` | LLM sampling temperature. |
| `thinkingBudgetTokens(int)` | `int` | `null` | Extended thinking budget (Anthropic only). |
| `timeoutSeconds(int)` | `int` | `600` | Overall agent execution timeout. |

### Tools

```java
import org.conductoross.conductor.ai.AgentTool;

Agent agent = Agent.builder()
    .name("tool_agent")
    .model("openai/gpt-4o-mini")
    .tools(AgentTool.from(new MyTools()))          // from @Tool-annotated POJO
    .tools(HttpTool.builder()
        .name("search")
        .url("https://api.example.com/search")
        .method("GET").build())                    // HTTP tool
    .build();
```

See [Tools](tools.md) for all tool types.

### Multi-agent

```java
Agent pipeline = writer.then(editor);             // .then() = Strategy.SEQUENTIAL shorthand

Agent team = Agent.builder()
    .name("team")
    .model("openai/gpt-4o-mini")
    .agents(writer, editor, reviewer)
    .strategy(Strategy.PARALLEL)
    .build();
```

See [Multi-Agent](multi-agent.md) for all strategies.

### Guardrails

```java
import org.conductoross.conductor.ai.guardrail.GuardrailDef;

Agent agent = Agent.builder()
    .name("safe_agent")
    .model("openai/gpt-4o-mini")
    .guardrails(
        GuardrailDef.regex("no_phone", Position.OUTPUT, "\\d{10}", OnFail.BLOCK),
        GuardrailDef.llm("tone_check", Position.OUTPUT, "Is the tone professional?")
    )
    .build();
```

See [Guardrails](guardrails.md).

### Credentials

Declare which secrets the agent's tools require. The SDK fetches them from the Agentspan secrets store at runtime and injects them into tool context.

```java
Agent agent = Agent.builder()
    .name("github_agent")
    .model("openai/gpt-4o-mini")
    .credentials("GITHUB_TOKEN", "JIRA_API_KEY")
    .build();

// In the tool:
public String createIssue(String title, ToolContext ctx) {
    String token = Credentials.get("GITHUB_TOKEN", ctx);
    // ...
}
```

### Code execution

```java
Agent agent = Agent.builder()
    .name("coder")
    .model("openai/gpt-4o-mini")
    .localCodeExecution(true)
    .allowedLanguages(List.of("python", "javascript"))
    .codeExecutionTimeout(30)          // seconds per execution
    .build();
```

### Callbacks

Intercept the agent loop before or after each model call:

```java
Agent agent = Agent.builder()
    .name("observed_agent")
    .model("openai/gpt-4o-mini")
    .beforeModelCallback(ctx -> {
        System.out.println("Calling LLM with: " + ctx.get("messages"));
        return ctx;  // return modified context or the original
    })
    .afterModelCallback(ctx -> {
        System.out.println("LLM replied: " + ctx.get("output"));
        return ctx;
    })
    .build();
```

### Fallback

Run a second agent if the first exceeds `fallbackMaxTurns`:

```java
Agent agent = Agent.builder()
    .name("primary")
    .model("openai/gpt-4o-mini")
    .fallback(Agent.builder().name("backup").model("anthropic/claude-haiku-4-5-20251001").build())
    .fallbackMaxTurns(5)
    .build();
```

---

## Running an agent

### AgentRuntime

`AgentRuntime` is the SDK entry point. Use try-with-resources — `close()` stops worker threads and releases HTTP connections.

```java
try (AgentRuntime runtime = new AgentRuntime()) {
    AgentResult result = runtime.run(agent, "Hello!");
}
```

| Method | Returns | Description |
|---|---|---|
| `run(agent, prompt)` | `AgentResult` | Blocking — waits for completion. |
| `run(agent, prompt, plan)` | `AgentResult` | Blocking with a deterministic plan. |
| `runAsync(agent, prompt)` | `CompletableFuture<AgentResult>` | Non-blocking. |
| `start(agent, prompt)` | `AgentHandle` | Fire-and-forget; returns a handle to poll or approve. |
| `startAsync(agent, prompt)` | `CompletableFuture<AgentHandle>` | Non-blocking start. |
| `stream(agent, prompt)` | `AgentStream` | Blocking iterator over events. |
| `streamAsync(agent, prompt)` | `CompletableFuture<AgentStream>` | Non-blocking stream. |
| `plan(agent)` | `Map<String,Object>` | Compile only — returns the workflow definition without executing. |
| `deploy(Agent...)` | `List<DeploymentInfo>` | Register workflow definitions without running them. |
| `serve(Agent...)` | `void` | Long-running worker mode — keeps polling indefinitely. |
| `resume(executionId, agent)` | `AgentHandle` | Resume a suspended execution. |
| `schedules()` | `Schedules` | Access the scheduling API. |

### AgentResult

```java
AgentResult result = runtime.run(agent, "prompt");

result.getOutput();          // String — final LLM output
result.getStatus();          // AgentStatus.COMPLETED / FAILED / TERMINATED / TIMED_OUT
result.getExecutionId();     // String — Conductor workflow ID
result.getToolCalls();       // List<Map<String,Object>> — all tool invocations
result.getEvents();          // List<AgentEvent> — full event log
result.getTokenUsage();      // TokenUsage — prompt/completion/total tokens
result.isSuccess();          // true if status == COMPLETED
result.getError();           // String — error message if failed
```

### AgentHandle

Returned by `start()` — lets you poll, approve, or stream after the fact:

```java
AgentHandle handle = runtime.start(agent, "prompt");
String executionId = handle.getExecutionId();

// Poll until done (blocks the calling thread)
AgentResult result = handle.waitForResult();

// Or approve a human-in-the-loop step
handle.approve("Looks good");
handle.reject("Not acceptable");
```

### Concurrency

`AgentRuntime` is thread-safe. Share one instance across threads rather than creating one per request.

```java
// Good — one shared runtime
private static final AgentRuntime runtime = new AgentRuntime();

// Run multiple agents concurrently
List<CompletableFuture<AgentResult>> futures = prompts.stream()
    .map(p -> runtime.runAsync(agent, p))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```
