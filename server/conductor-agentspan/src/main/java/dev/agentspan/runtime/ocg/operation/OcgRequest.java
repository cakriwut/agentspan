/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP request factory for OCG endpoints. Centralises the bearer-auth
 * header, default timeouts, and JSON body serialization so every
 * {@link OcgOperation} produces requests with the same baseline headers
 * and policies — drift here would mean some endpoints get authenticated
 * and others silently don't.
 */
public final class OcgRequest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private OcgRequest() {}

    /**
     * Common {@link HttpRequest.Builder} for every OCG endpoint: read
     * timeout, JSON accept, and the optional bearer token. Operations call
     * {@code .uri(...).METHOD(...).build()} on the returned builder.
     */
    public static HttpRequest.Builder base(OcgTarget target) {
        HttpRequest.Builder b = HttpRequest.newBuilder().timeout(READ_TIMEOUT).header("Accept", "application/json");
        if (target.hasAuth()) {
            b.header("Authorization", target.authHeader());
        }
        return b;
    }

    /** POST with a JSON-serialized body. */
    public static HttpRequest postJson(OcgTarget target, URI uri, Object body) throws IOException {
        String json = OcgInputs.writeJson(body);
        return base(target)
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    /** GET with no body. */
    public static HttpRequest get(OcgTarget target, URI uri) {
        return base(target).uri(uri).GET().build();
    }

    /** DELETE with no body. */
    public static HttpRequest delete(OcgTarget target, URI uri) {
        return base(target).uri(uri).DELETE().build();
    }
}
