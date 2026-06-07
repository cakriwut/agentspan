// Copyright (c) 2025 Agentspan
// Licensed under the MIT License. See LICENSE file in the project root for details.

package org.conductoross.conductor.ai.internal;

import java.util.List;
import java.util.Map;

import org.conductoross.conductor.ai.Agent;
import org.conductoross.conductor.ai.plans.Plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Request payload for {@code POST /api/agent/compile}, {@code /deploy}, and {@code /start}.
 *
 * <p>All three endpoints share the same server-side {@code StartRequest} DTO. Fields that
 * are not relevant to an endpoint (e.g. {@code prompt} for compile) are left null and
 * omitted from the wire by {@link JsonInclude#NON_NULL}.
 *
 * <p>Agent definition is carried in one of two shapes:
 * <ul>
 *   <li><b>Native agents</b>: {@code agentConfig} holds the {@link Agent} object.
 *       {@link AgentConfigSerializer.AsJson} serializes it to the camelCase map
 *       the server's {@code AgentConfig} DTO expects.</li>
 *   <li><b>Framework-backed agents</b> (OpenAI, Google ADK, LangChain, Skill):
 *       {@code framework} identifies the normalizer; {@code rawConfig} holds the
 *       {@link Agent} object serialized the same way via the same serializer.</li>
 * </ul>
 *
 * <p>Build via {@link #nativeAgent(Agent)} or {@link #frameworkAgent(String, Agent)},
 * then chain builder methods for execution-specific fields ({@code prompt}, {@code runId}, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AgentRequest {

    // ── Agent definition (mutually exclusive) ────────────────────────────

    /**
     * Native agent definition. Serialized to the server's {@code AgentConfig} wire format
     * by {@link AgentConfigSerializer.AsJson}.
     */
    @JsonProperty("agentConfig")
    @JsonSerialize(using = AgentConfigSerializer.AsJson.class)
    private final Agent agentConfig;

    /** Framework identifier: {@code "openai"}, {@code "google_adk"}, {@code "langchain"}, {@code "skill"}, etc. */
    @JsonProperty("framework")
    private final String framework;

    /**
     * Framework-specific agent config. Serialized by {@link AgentConfigSerializer.AsJson}
     * — same format as {@link #agentConfig}, routed through the matching server-side normalizer.
     */
    @JsonProperty("rawConfig")
    @JsonSerialize(using = AgentConfigSerializer.AsJson.class)
    private final Agent rawConfig;

    // ── Execution fields (only meaningful for /start) ────────────────────

    /** User's input message. Required for {@code /start}; omitted for compile/deploy. */
    @JsonProperty("prompt")
    private final String prompt;

    /** Session/memory identifier for stateful agents. */
    @JsonProperty("sessionId")
    private final String sessionId;

    /**
     * Per-execution isolation UUID for stateful agents. The server maps all worker
     * tasks to this domain so concurrent executions don't steal each other's tasks.
     */
    @JsonProperty("runId")
    private final String runId;

    /**
     * Deterministic plan for {@code PLAN_EXECUTE} strategy. Bypasses the planner LLM.
     * Serialized as {@code "static_plan"} by {@link Plan.AsJson} to match the server's
     * {@code @JsonProperty("static_plan")} on {@code StartRequest.staticPlan}.
     */
    @JsonProperty("static_plan")
    @JsonSerialize(using = Plan.AsJson.class)
    private final Plan staticPlan;

    // ── Optional fields ──────────────────────────────────────────────────

    /** Media file URLs or base64 strings attached to the prompt. */
    @JsonProperty("media")
    private final List<String> media;

    /**
     * Arbitrary key-value context injected into the workflow input.
     * Intentionally untyped — the server treats it as a free-form map.
     */
    @JsonProperty("context")
    private final Map<String, Object> context;

    /** Client-supplied deduplication key; the server deduplicates starts with the same key. */
    @JsonProperty("idempotencyKey")
    private final String idempotencyKey;

    /** Credential names the server should inject at runtime. */
    @JsonProperty("credentials")
    private final List<String> credentials;

    /** Per-execution timeout override (seconds). */
    @JsonProperty("timeoutSeconds")
    private final Integer timeoutSeconds;

    private AgentRequest(Builder b) {
        this.agentConfig = b.agentConfig;
        this.framework = b.framework;
        this.rawConfig = b.rawConfig;
        this.prompt = b.prompt;
        this.sessionId = b.sessionId;
        this.runId = b.runId;
        this.staticPlan = b.staticPlan;
        this.media = b.media;
        this.context = b.context;
        this.idempotencyKey = b.idempotencyKey;
        this.credentials = b.credentials;
        this.timeoutSeconds = b.timeoutSeconds;
    }

    /** Start building a request for a native (non-framework) agent. */
    public static Builder nativeAgent(Agent agent) {
        return new Builder().agentConfig(agent);
    }

    /** Start building a request for a framework-backed agent (OpenAI, ADK, LangChain, Skill). */
    public static Builder frameworkAgent(String framework, Agent agent) {
        return new Builder().framework(framework).rawConfig(agent);
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static final class Builder {
        private Agent agentConfig;
        private String framework;
        private Agent rawConfig;
        private String prompt;
        private String sessionId;
        private String runId;
        private Plan staticPlan;
        private List<String> media;
        private Map<String, Object> context;
        private String idempotencyKey;
        private List<String> credentials;
        private Integer timeoutSeconds;

        private Builder agentConfig(Agent v) {
            this.agentConfig = v;
            return this;
        }

        private Builder framework(String v) {
            this.framework = v;
            return this;
        }

        private Builder rawConfig(Agent v) {
            this.rawConfig = v;
            return this;
        }

        public Builder prompt(String v) {
            this.prompt = v;
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder runId(String v) {
            this.runId = v;
            return this;
        }

        public Builder staticPlan(Plan v) {
            this.staticPlan = v;
            return this;
        }

        public Builder media(List<String> v) {
            this.media = v;
            return this;
        }

        public Builder context(Map<String, Object> v) {
            this.context = v;
            return this;
        }

        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        public Builder credentials(List<String> v) {
            this.credentials = v;
            return this;
        }

        public Builder timeoutSeconds(Integer v) {
            this.timeoutSeconds = v;
            return this;
        }

        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }
}
