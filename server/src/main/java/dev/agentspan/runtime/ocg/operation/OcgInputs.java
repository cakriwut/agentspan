/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg.operation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.math.NumberUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Map and JSON utilities shared by every {@link OcgOperation} and by
 * {@code OcgRequestTask}. Stateless; Apache Commons wherever it gives a
 * cleaner one-liner than rolling our own.
 *
 * <p>Owns the single {@link ObjectMapper} used across the OCG subsystem so
 * there's one source of truth for JSON shape and no per-class instances.</p>
 */
public final class OcgInputs {

    /** Shared Jackson mapper. {@link ObjectMapper} is thread-safe. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private OcgInputs() {}

    /**
     * Build a new {@link LinkedHashMap} containing only the requested keys
     * that are present and non-null in {@code src}. Insertion order is the
     * key order passed in — matters for stable JSON serialization of
     * request bodies.
     */
    public static Map<String, Object> pick(Map<?, ?> src, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = src.get(key);
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Extract a required string input. Throws {@link IllegalArgumentException}
     * (via {@link Validate}) with a consistent message when missing or blank,
     * giving the OCG sub-agent's LLM a debuggable failure reason instead of
     * an opaque NPE downstream.
     */
    public static String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        Validate.isTrue(
                value instanceof String && StringUtils.isNotBlank((String) value),
                "Missing or empty required input '%s'",
                key);
        return (String) value;
    }

    /**
     * Coerce {@code value} to int, falling back when it's null, non-numeric,
     * or an unparseable string. Number → intValue(), String → parsed via
     * {@link NumberUtils#toInt(String, int)} so we don't throw on bad input.
     */
    public static int intOrDefault(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return NumberUtils.toInt(s, fallback);
        return fallback;
    }

    /**
     * Parse a JSON body without throwing on malformed input. Blank → empty
     * map; parse failure → {@code {"raw": <body>}} so the original text is
     * still visible downstream rather than swallowed.
     */
    public static Object parseJsonLenient(String body) {
        if (StringUtils.isBlank(body)) return Map.of();
        try {
            return MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            return Map.of("raw", body);
        }
    }

    /** Serialize an object to JSON using the shared mapper. */
    public static String writeJson(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }
}
