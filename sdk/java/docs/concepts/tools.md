# Tools

Tools give agents the ability to take actions. In Agentspan, each tool invocation runs as a Conductor task — distributed, retryable, and observable in the workflow audit log.

## Java method tools (`@Tool`)

Annotate methods with `@Tool` and wrap the containing object with `AgentTool.from()`:

```java
import org.conductoross.conductor.ai.annotations.Tool;
import org.conductoross.conductor.ai.AgentTool;
import org.conductoross.conductor.ai.model.ToolContext;

public class SearchTools {

    @Tool(name = "web_search", description = "Search the web for current information")
    public String search(String query) {
        return callSearchApi(query);
    }

    @Tool(name = "get_page", description = "Fetch the content of a URL")
    public String getPage(String url) {
        return fetchUrl(url);
    }
}

Agent agent = Agent.builder()
    .name("research_agent")
    .model("openai/gpt-4o-mini")
    .tools(AgentTool.from(new SearchTools()))
    .build();
```

### Tool parameters

The LLM sees a JSON Schema built from the method signature. Supported parameter types: `String`, `int`/`Integer`, `long`/`Long`, `double`/`Double`, `boolean`/`Boolean`, `List<T>`, `Map<String,Object>`, and any `record` or POJO with public getters.

```java
@Tool(name = "create_issue", description = "Create a GitHub issue")
public String createIssue(
    String title,
    String body,
    List<String> labels
) {
    // ...
}
```

### ToolContext

Inject `ToolContext` as the last parameter to access execution metadata, session state, and credentials:

```java
@Tool(name = "send_email", description = "Send an email")
public String sendEmail(String to, String subject, String body, ToolContext ctx) {
    String apiKey = Credentials.get("SENDGRID_API_KEY", ctx);
    String executionId = ctx.getExecutionId();
    // ...
}
```

### Credentials in tools

Declare which secrets a tool needs via `Agent.builder().credentials(...)`. The SDK fetches them from the Agentspan secrets store and injects them at runtime:

```java
Agent agent = Agent.builder()
    .name("github_agent")
    .credentials("GITHUB_TOKEN")
    .tools(AgentTool.from(new GitHubTools()))
    .build();

// Store the secret once via the CLI or API:
// agentspan secrets set GITHUB_TOKEN ghp_xxxxx
```

---

## HTTP tools

Call any REST endpoint without writing Java code:

```java
import org.conductoross.conductor.ai.tools.HttpTool;

ToolDef searchTool = HttpTool.builder()
    .name("search")
    .description("Search for products")
    .url("https://api.mystore.com/search")
    .method("GET")
    .build();

Agent agent = Agent.builder()
    .name("shop_agent")
    .model("openai/gpt-4o-mini")
    .tools(searchTool)
    .build();
```

---

## MCP tools

Connect to any [Model Context Protocol](https://modelcontextprotocol.io) server:

```java
import org.conductoross.conductor.ai.tools.McpTool;

ToolDef mcpTool = McpTool.builder()
    .name("filesystem")
    .description("Access the local filesystem via MCP")
    .serverUrl("http://localhost:3001")
    .build();
```

---

## CLI tools

Run shell commands as tool calls. The command runs in your local process; the agent decides the arguments.

```java
import org.conductoross.conductor.ai.tools.CliConfig;

Agent agent = Agent.builder()
    .name("devops_agent")
    .model("openai/gpt-4o-mini")
    .instructions("Run git commands as requested.")
    .cliConfig(CliConfig.builder()
        .command("git")
        .allowedCommands(List.of("git status", "git log", "git diff"))
        .timeout(30)
        .build())
    .build();
```

!!! warning "Security"
    Use `allowedCommands` to restrict which commands the agent can execute. Without a whitelist, the agent can run any command the JVM user has permission to execute.

---

## Human-in-the-loop tools

Pause the agent and wait for a human decision:

```java
import org.conductoross.conductor.ai.tools.HumanTool;

ToolDef approvalTool = HumanTool.create(
    "approve_deployment",
    "Request human approval before deploying to production"
);

Agent agent = Agent.builder()
    .name("deploy_agent")
    .model("openai/gpt-4o-mini")
    .tools(approvalTool)
    .build();
```

When the agent calls this tool, execution pauses. Resume it with:

```java
AgentHandle handle = runtime.start(agent, "Deploy version 2.1 to production");

// Later, once a human decides:
handle.approve("Approved by Alice");
// or
handle.reject("Needs more testing");
```

The workflow can wait days — it's stored durably in Conductor.

---

## PDF generation

```java
import org.conductoross.conductor.ai.tools.PdfTool;

ToolDef pdfTool = PdfTool.create("generate_report", "Generate a formatted PDF report");
```

---

## Image / media tools

```java
import org.conductoross.conductor.ai.tools.MediaTools;

ToolDef imageTool = MediaTools.imageTool(
    "generate_image",
    "Generate an image from a description",
    "openai",
    "dall-e-3"
);
```

---

## Async message tools

Wait for an external event before continuing:

```java
import org.conductoross.conductor.ai.tools.WaitForMessageTool;

ToolDef waitTool = WaitForMessageTool.create(
    "wait_for_payment",
    "Wait until the payment webhook confirms the transaction"
);
```

---

## Agent tools (sub-agents)

Any `Agent` can be a tool for another agent. This is the building block for all multi-agent patterns:

```java
Agent researcher = Agent.builder()
    .name("researcher")
    .model("openai/gpt-4o-mini")
    .instructions("Research a topic and return a summary.")
    .build();

Agent writer = Agent.builder()
    .name("writer")
    .model("openai/gpt-4o-mini")
    .instructions("Write an article given a research summary.")
    .agents(researcher)              // researcher becomes a callable tool
    .build();
```

See [Multi-Agent](multi-agent.md) for orchestration patterns.
