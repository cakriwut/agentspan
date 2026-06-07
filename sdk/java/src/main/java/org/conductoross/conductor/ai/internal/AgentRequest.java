// Copyright (c) 2025 Agentspan
// Licensed under the MIT License. See LICENSE file in the project root for details.

package org.conductoross.conductor.ai.internal;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for {@code POST /api/agent/compile}, {@code /deploy}, and {@code /start}.
 *
 * <p>All three endpoints share the same server-side {@code StartRequest} DTO. Fields that
 * are not relevant to an endpoint (e.g. {@code prompt} for compile) are left null and
 * omitted from the wire by {@link JsonInclude#NON_NULL}.
 *
 * <p>Agent definition is sent in one of two shapes:
 * <ul>
 *   <li><b>Native agents</b>: {@code agentConfig} holds the serialized agent tree.</li>
 *   <li><b>Framework-backed agents</b> (OpenAI, Google ADK, LangChain, Skill):
 *       {@code framework} + {@code rawConfig}. The server routes these through the
 *       matching {@code AgentConfigNormalizer} before compilation.</li>
 * </ul>
 *
 * <p>Build via {@link #nativeAgent(Map)} or {@link #frameworkAgent(String, Map)},
 * then configure with the builder methods.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AgentRequest {

    // ── Agent definition (mutually exclusive) ────────────────────────────

    /** Native agent definition produced by {@code AgentConfigSerializer.serialize()}. */
    @JsonProperty("agentConfig")
    private final Map<String, Object> agentConfig;

    /** Framework identifier: {@code "openai"}, {@code "google_adk"}, {@code "langchain"}, {@code "skill"}, etc. */
    @JsonProperty("framework")
    private final String framework;

    /** Serialized framework-specific agent config. Required when {@link #framework} is set. */
    @JsonProperty("rawConfig")
    private final Map<String, Object> rawConfig;

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
     * Serialized as {@code "static_plan"} to match the server's {@code @JsonProperty}.
     */
    @JsonProperty("static_plan")
    private final Map<String, Object> staticPlan;

    // ── Optional fields ──────────────────────────────────────────────────

    /** Media file URLs or base64 strings attached to the prompt. */
    @JsonProperty("media")
    private final List<String> media;

    /** Arbitrary key-value context injected into the workflow input. */
    @JsonProperty("context")
    private final Map<String, Object> context;

    /** Client-supplied deduplication key; the server ignores duplicate starts. */
    @JsonProperty("idempotencyKey")
    private final String idempotencyKey;

    /** Credential names the server should inject at runtime. */
    @JsonProperty("credentials")
    private final List<String> credentials;

    /** Reference to a server-registered skill package. Used with {@code framework="skill"}. */
    @JsonProperty("skillRef")
    private final Map<String, Object> skillRef;

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
        this.skillRef = b.skillRef;
        this.timeoutSeconds = b.timeoutSeconds;
    }

    /** Start building a request for a native (non-framework) agent. */
    public static Builder nativeAgent(Map<String, Object> agentConfig) {
        return new Builder().agentConfig(agentConfig);
    }

    /** Start building a request for a framework-backed agent (OpenAI, ADK, LangChain, Skill). */
    public static Builder frameworkAgent(String framework, Map<String, Object> rawConfig) {
        return new Builder().framework(framework).rawConfig(rawConfig);
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static final class Builder {
        private Map<String, Object> agentConfig;
        private String framework;
        private Map<String, Object> rawConfig;
        private String prompt;
        private String sessionId;
        private String runId;
        private Map<String, Object> staticPlan;
        private List<String> media;
        private Map<String, Object> context;
        private String idempotencyKey;
        private List<String> credentials;
        private Map<String, Object> skillRef;
        private Integer timeoutSeconds;

        private Builder agentConfig(Map<String, Object> v) {
            this.agentConfig = v;
            return this;
        }

        private Builder framework(String v) {
            this.framework = v;
            return this;
        }

        private Builder rawConfig(Map<String, Object> v) {
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

        public Builder staticPlan(Map<String, Object> v) {
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

        public Builder skillRef(Map<String, Object> v) {
            this.skillRef = v;
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
