/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration for the OCG (Open Context Graph) execution layer.
 *
 * <p>OCG agents and tools are declared in user code via the SDK
 * ({@code ocg_agent()} / {@code ocg_tools()}), and every OCG tool binds its
 * own instance (url + credential-store reference) — there is no server-side
 * OCG instance configuration. These properties only control whether the
 * execution layer exists and how responses are shaped.</p>
 */
@Data
@ConfigurationProperties(prefix = "agentspan.ocg")
public class OcgProperties {

    /**
     * Whether the OCG execution layer (the {@code OCG_*} system tasks) is
     * available. Disabling rejects agent starts that declare OCG tools.
     * (Lombok generates {@code isEnabled()} for this field.)
     */
    private boolean enabled = true;

    /**
     * Per-response truncation cap (post-projection, JSON-serialized) for the
     * {@code OCG_*} system tasks. Mirrors the Python reference helper
     * {@code _enforce_response_cap}.
     */
    private int responseCapChars = 8192;
}
