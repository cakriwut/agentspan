/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

/**
 * Resolves {@code #{NAME}} credential placeholders in a per-tool OCG auth
 * header value (standalone mode). In embedded mode the host substitutes
 * {@code ${workflow.secrets.NAME}} before the task ever starts, so values
 * arrive with no placeholders and this resolver is never consulted.
 *
 * <p>Mirrors the contract of {@code CredentialAwareHttpTask}: resolution is
 * scoped to the calling user via the execution token in
 * {@code __agentspan_ctx__}, and resolved values exist only in memory —
 * they are never written back to the task model.</p>
 */
@FunctionalInterface
public interface OcgCredentialResolver {

    /**
     * Resolve every {@code #{NAME}} placeholder in {@code value}.
     *
     * @param value        the auth header value, e.g. {@code "Bearer #{OCG_US_KEY}"}
     * @param agentspanCtx the {@code __agentspan_ctx__} task input (map with an
     *                     {@code execution_token} entry, or the raw token string)
     * @return the fully resolved value, or {@code null} when resolution is not
     *         possible (missing/invalid token, unknown credential name)
     */
    String resolve(String value, Object agentspanCtx);
}
