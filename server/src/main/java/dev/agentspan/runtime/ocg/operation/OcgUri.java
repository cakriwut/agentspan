/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import dev.agentspan.runtime.ocg.OcgProperties;

/**
 * URI builder for OCG endpoints. Every OCG path sits under {@code /api/v1}
 * on the configured base URL; this helper handles the trailing-slash
 * trimming and the prefix attachment so operations only spell out the
 * endpoint-specific path segments.
 *
 * <p>Path segments and query params go through {@link UriComponentsBuilder},
 * which URL-encodes correctly — no hand-rolled {@code URLEncoder} calls
 * scattered across operations.</p>
 */
public final class OcgUri {

    /** OCG's stable API version prefix. Bump as a single point of change. */
    public static final String API_PREFIX_V1 = "/api/v1";

    private OcgUri() {}

    /**
     * Returns a {@link UriComponentsBuilder} rooted at
     * {@code <ocg-url>/api/v1}. Operations chain {@code .pathSegment(...)}
     * + {@code .queryParam(...)} on top.
     */
    public static UriComponentsBuilder forApi(OcgProperties properties) {
        String base = StringUtils.removeEnd(StringUtils.defaultString(properties.getUrl()), "/");
        return UriComponentsBuilder.fromUriString(base + API_PREFIX_V1);
    }
}
