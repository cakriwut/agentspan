/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

/**
 * The OCG instance a single call targets: base URL plus the full
 * {@code Authorization} header value (already resolved — no credential
 * placeholders survive past {@code OcgRequestTask}).
 *
 * <p>Resolved per call by {@code OcgRequestTask}: a tool-bound instance
 * (the {@code __ocg_url} / {@code __ocg_auth} task inputs compiled from the
 * SDK's {@code url=} / {@code credential=}) wins over the server-wide
 * default in {@code OcgProperties}. Operations only ever see this record,
 * so they cannot accidentally reach for the default config.</p>
 *
 * @param baseUrl    base URL of the OCG instance (no trailing slash required)
 * @param authHeader full Authorization header value (e.g. {@code "Bearer …"}),
 *                   or {@code null} / blank for unauthenticated instances
 */
public record OcgTarget(String baseUrl, String authHeader) {

    public boolean hasAuth() {
        return authHeader != null && !authHeader.isBlank();
    }
}
