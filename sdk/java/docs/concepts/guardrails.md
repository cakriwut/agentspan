# Guardrails

Guardrails validate or modify agent input and output. They run before the agent sees a message (`INPUT`) or after the agent produces a response (`OUTPUT`).

## Quick example

```java
import org.conductoross.conductor.ai.guardrail.GuardrailDef;
import org.conductoross.conductor.ai.enums.OnFail;
import org.conductoross.conductor.ai.enums.Position;

Agent agent = Agent.builder()
    .name("safe_agent")
    .model("openai/gpt-4o-mini")
    .guardrails(
        // Block output that contains a phone number pattern
        GuardrailDef.regex("no_phone_numbers", Position.OUTPUT,
            "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b", OnFail.BLOCK),

        // Ask the LLM to check tone; fix if wrong
        GuardrailDef.llm("professional_tone", Position.OUTPUT,
            "Is the response professional and free of slang?", OnFail.FIX)
    )
    .build();
```

## Guardrail types

### Regex

Match a pattern and take action if found (or not found).

```java
GuardrailDef.regex(
    "name",             // guardrail ID
    Position.OUTPUT,    // INPUT or OUTPUT
    "\\bpassword\\b",   // Java regex pattern
    OnFail.BLOCK        // what to do on match
)
```

### LLM

Ask a language model to evaluate the content. The model answers a yes/no question.

```java
GuardrailDef.llm(
    "safe_content",
    Position.OUTPUT,
    "Does the response contain harmful or offensive content?",
    OnFail.BLOCK
)
```

### Custom (Java function)

Write a `GuardrailDef` with a Java function for full control:

```java
GuardrailDef custom = GuardrailDef.of("length_check", Position.OUTPUT, content -> {
    if (content.length() > 5000) {
        return GuardrailResult.fail("Response too long: " + content.length() + " chars");
    }
    return GuardrailResult.pass();
});
```

## Positions

| Constant | When it runs |
|---|---|
| `Position.INPUT` | Before the user message reaches the agent's LLM |
| `Position.OUTPUT` | After the agent produces a response, before it's returned |

## OnFail actions

| Constant | Effect |
|---|---|
| `OnFail.BLOCK` | Terminate the agent run with an error |
| `OnFail.FIX` | Ask the LLM to rewrite the output to pass the guardrail |
| `OnFail.WARN` | Log a warning but continue |

## GuardrailResult

Custom guardrails return `GuardrailResult`:

```java
GuardrailResult.pass()                         // guardrail passed
GuardrailResult.fail("reason")                 // guardrail failed
GuardrailResult.fix("rewritten output")        // provide a fixed replacement
```
