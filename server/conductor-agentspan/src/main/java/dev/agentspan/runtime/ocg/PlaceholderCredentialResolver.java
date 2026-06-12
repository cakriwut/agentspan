/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agentspan.runtime.credentials.CredentialResolutionService;
import dev.agentspan.runtime.credentials.ExecutionTokenService;

/**
 * Default {@link OcgCredentialResolver}: resolves {@code #{NAME}} placeholders
 * through the credential store, scoped to the user identified by the execution
 * token in {@code __agentspan_ctx__}. Same token/store contract as
 * {@code CredentialAwareHttpTask} — OCG must not invent a second secret path.
 */
public class PlaceholderCredentialResolver implements OcgCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderCredentialResolver.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{([\\w.]+)}");

    private final ExecutionTokenService tokenService;
    private final CredentialResolutionService resolutionService;

    public PlaceholderCredentialResolver(
            ExecutionTokenService tokenService, CredentialResolutionService resolutionService) {
        this.tokenService = tokenService;
        this.resolutionService = resolutionService;
    }

    @Override
    public String resolve(String value, Object agentspanCtx) {
        if (value == null || !PLACEHOLDER.matcher(value).find()) {
            return value;
        }
        String userId = extractUserId(agentspanCtx);
        if (userId == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String credValue = resolutionService.resolve(userId, m.group(1));
            if (credValue == null) {
                log.warn("OCG credential '{}' not found for user", m.group(1));
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(credValue));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String extractUserId(Object ctx) {
        String token = null;
        if (ctx instanceof Map<?, ?> ctxMap) {
            token = (String) ctxMap.get("execution_token");
        } else if (ctx instanceof String s) {
            token = s;
        }
        if (token == null) {
            return null;
        }
        try {
            return tokenService.validate(token).userId();
        } catch (Exception e) {
            log.warn("Failed to validate token for OCG credential resolution: {}", e.getMessage());
            return null;
        }
    }
}
