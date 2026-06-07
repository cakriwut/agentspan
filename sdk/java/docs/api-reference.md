# API Reference

Complete method signatures for the Agentspan Java SDK public API.

## AgentRuntime

The SDK entry point. Thread-safe — share one instance.

```java
// Constructors
AgentRuntime()                                    // reads AGENTSPAN_* env vars
AgentRuntime(AgentConfig config)                  // env vars + explicit tuning
AgentRuntime(ApiClient client)                    // explicit client
AgentRuntime(ApiClient client, AgentConfig config)

// Static factories for ApiClient
static ApiClient clientFromEnv()
static ApiClient client(String serverUrl)
static ApiClient client(String serverUrl, String authKey, String authSecret)
```

### Run

```java
AgentResult  run(Agent agent, String prompt)
AgentResult  run(Agent agent, String prompt, Plan plan)

CompletableFuture<AgentResult>  runAsync(Agent agent, String prompt)
CompletableFuture<AgentResult>  runAsync(Agent agent, String prompt, Plan plan)
```

### Start (fire-and-forget)

```java
AgentHandle  start(Agent agent, String prompt)
CompletableFuture<AgentHandle>  startAsync(Agent agent, String prompt)
CompletableFuture<AgentHandle>  startAsync(Agent agent, String prompt, Plan plan)
```

### Stream

```java
AgentStream  stream(Agent agent, String prompt)
CompletableFuture<AgentStream>  streamAsync(Agent agent, String prompt)
```

### Deploy / serve

```java
Map<String,Object>         plan(Agent agent)                    // compile only, no run
List<DeploymentInfo>       deploy(Agent... agents)
DeploymentInfo             deploy(Agent agent, List<Schedule> schedules)
CompletableFuture<List<DeploymentInfo>>  deployAsync(Agent... agents)
void                       serve(Agent... agents)               // blocks indefinitely
```

### Resume / schedule

```java
AgentHandle                         resume(String executionId, Agent agent)
CompletableFuture<AgentHandle>      resumeAsync(String executionId, Agent agent)
Schedules                           schedules()
```

### Lifecycle

```java
void  shutdown()      // stop workers, release HTTP connections
void  close()         // alias for shutdown(); implements AutoCloseable
```

---

## Agent.Builder

```java
Agent.builder()
    // Identity
    .name(String)                          // required
    .model(String)                         // required
    .instructions(String)
    .instructionsTemplate(PromptTemplate)
    .introduction(String)
    .metadata(Map<String,Object>)

    // LLM
    .maxTurns(int)                         // default 25
    .maxTokens(int)
    .temperature(double)
    .thinkingBudgetTokens(int)             // Anthropic extended thinking
    .timeoutSeconds(int)                   // default 600

    // Tools
    .tools(List<ToolDef>)
    .tools(ToolDef...)

    // Multi-agent
    .agents(List<Agent>)
    .agents(Agent...)
    .strategy(Strategy)
    .router(Agent)
    .handoffs(List<Handoff>)
    .handoffs(Handoff...)
    .allowedTransitions(Map<String,List<String>>)

    // Termination
    .termination(TerminationCondition)

    // Guardrails
    .guardrails(List<GuardrailDef>)
    .guardrails(GuardrailDef...)

    // Auth
    .credentials(List<String>)
    .credentials(String...)

    // Code execution
    .localCodeExecution(boolean)
    .allowedLanguages(List<String>)
    .codeExecutionTimeout(int)             // seconds

    // Callbacks (intercept the agent loop)
    .beforeModelCallback(Function<Map<String,Object>, Map<String,Object>>)
    .afterModelCallback(Function<Map<String,Object>, Map<String,Object>>)
    .beforeAgentCallback(Function<Map<String,Object>, Map<String,Object>>)
    .afterAgentCallback(Function<Map<String,Object>, Map<String,Object>>)
    .callbacks(List<CallbackHandler>)
    .callbacks(CallbackHandler...)

    // Stateful (session isolation)
    .sessionId(String)
    .stateful(boolean)

    // Advanced
    .outputType(Class<?>)                  // structured output
    .fallback(Agent)
    .fallbackMaxTurns(int)
    .planner(Agent)
    .plannerContext(List<Context>)
    .plannerContext(String...)
    .prefillTools(List<PrefillToolCall>)
    .synthesize(boolean)
    .enablePlanning(boolean)
    .baseUrl(String)
    .gate(TextGate)
    .includeContents(String)
    .cliConfig(CliConfig)
    .requiredTools(String...)
    .stopWhen(String)                      // task name to stop on
    .allowedCommands(List<String>)
    .framework(String)                     // for bridge agents
    .frameworkConfig(Map<String,Object>)

    .build()
```

---

## AgentResult

```java
String                   getOutput()
AgentStatus              getStatus()        // COMPLETED | FAILED | TERMINATED | TIMED_OUT
String                   getExecutionId()
List<Map<String,Object>> getToolCalls()
List<AgentEvent>         getEvents()
TokenUsage               getTokenUsage()
boolean                  isSuccess()
String                   getError()
Map<String,Object>       getRawResult()
```

---

## AgentHandle

```java
String       getExecutionId()
AgentResult  waitForResult()                             // blocks; default 600s timeout
AgentResult  waitForResult(long timeoutMs, long pollMs)  // explicit timeout
boolean      waitUntilWaiting(long timeoutMs)            // wait for HITL pause
void         approve()
void         approve(String comment)
void         reject()
void         reject(String reason)
```

---

## AgentStream

`AgentStream` implements `Iterable<AgentEvent>` and `AutoCloseable`.

```java
try (AgentStream stream = runtime.stream(agent, prompt)) {
    for (AgentEvent event : stream) {
        EventType type = event.getType();    // MESSAGE | TOOL_CALL | TOOL_RESULT | …
        String content  = event.getContent();
        String toolName = event.getToolName();
        Map<String,Object> args   = event.getArgs();
        String executionId        = event.getExecutionId();
    }
}

// Approve from a stream
stream.approve(event);
stream.reject(event, "reason");
```

---

## Tool builders

```java
// Java method tools
AgentTool.from(Object pojo)                            // @Tool-annotated methods
AgentTool.from(Object pojo, String toolName)           // specific method

// HTTP
HttpTool.builder().name(String).description(String).url(String).method(String).build()

// MCP
McpTool.builder().name(String).description(String).serverUrl(String).build()

// Human
HumanTool.create(String name, String description)

// PDF
PdfTool.create(String name, String description)

// Wait for message
WaitForMessageTool.create(String name, String description)

// Image / media
MediaTools.imageTool(String name, String description, String provider, String model)
```

---

## Termination conditions

```java
MaxMessageTermination.of(int maxMessages)
StopMessageTermination.of(String stopMessage)
TextMentionTermination.of(String text)
TextMentionTermination.of(String text, boolean caseSensitive)
TokenUsageTermination.ofTotal(int maxTokens)

// Compose
condition.and(TerminationCondition other)   // both must be true
condition.or(TerminationCondition other)    // either must be true
```

---

## Handoffs

```java
OnTextMention.of(String text, String targetAgent)
OnToolResult.of(String toolName, String targetAgent)
OnToolResult.of(String toolName, String targetAgent, String resultContains)
new OnCondition(String targetAgent, Function<Map<String,Object>,Boolean> predicate)
```

---

## Schedules

```java
Schedules schedules = runtime.schedules();

schedules.save(Schedule schedule, String agentName)
schedules.get(String wireName)                         // → ScheduleInfo
schedules.list(String agentName)                       // → List<ScheduleInfo>
schedules.runNow(String wireName)
schedules.pause(String wireName)
schedules.resume(String wireName)
schedules.delete(String wireName)
schedules.nextNExecutions(String wireName, int n)      // → List<Long> (epoch ms)

// Schedule.builder()
Schedule.builder()
    .name(String)         // required
    .cron(String)         // required; standard 5-field cron
    .timezone(String)     // default "UTC"
    .input(Map)
    .description(String)
    .paused(boolean)
    .catchup(boolean)
    .startAt(long)        // epoch ms
    .endAt(long)          // epoch ms
    .build()
```

---

## Credentials

```java
// In a @Tool method (ToolContext required as last param):
String value = Credentials.get("SECRET_NAME", ctx);
String value = Credentials.getOrNull("SECRET_NAME", ctx);  // null if not found

// Store via CLI:
// agentspan secrets set SECRET_NAME value
```

---

## Skill

```java
Agent Skill.skill(Path path, String model)
Agent Skill.skill(Path path, String model, Map<String,String> agentModels)
Map<String,Agent> Skill.loadSkills(Path directory, String model)
```

---

## Framework bridges

```java
// LangChain4j
Agent LangChain4jAgent.from(String name, String model, String instructions, Object... tools)
boolean LangChain4jAgent.isLangChain4jTools(Object obj)

// OpenAI Agents SDK style
OpenAIAgent.builder()
    .name(String).model(String).instructions(String)
    .tools(Object...)        // @Tool-annotated POJOs
    .handoffs(Agent...)
    .outputType(String)
    .build()

// Google ADK
Agent AdkBridge.toAgentspan(BaseAgent adkAgent)
Agent.Builder AdkBridge.agentBuilder(BaseAgent adkAgent)
```

---

## AgentConfig

```java
new AgentConfig()                                 // defaults: 100ms poll, 1 thread
new AgentConfig(int pollIntervalMs, int threads)
AgentConfig.fromEnv()                             // reads AGENTSPAN_WORKER_* env vars

config.getWorkerPollIntervalMs()
config.getWorkerThreadCount()
```
