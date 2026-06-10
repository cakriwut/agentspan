/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.model.TaskModel;

import dev.agentspan.runtime.ocg.operation.OcgGetEntityOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryDeleteOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemoryReinforceOperation;
import dev.agentspan.runtime.ocg.operation.OcgMemorySetOperation;
import dev.agentspan.runtime.ocg.operation.OcgNeighborhoodOperation;
import dev.agentspan.runtime.ocg.operation.OcgQueryOperation;

/**
 * Unit tests for {@link OcgRequestTask}.
 *
 * <p>Exercises the thin orchestrator with each strategy plugged in to
 * pin the per-endpoint URL/method contract, projection, capping, and
 * error handling. End-to-end behaviour through the strategy is the
 * surface most likely to drift when an operation is refactored.</p>
 */
class OcgRequestTaskTest {

    private static OcgProperties props(String url) {
        OcgProperties p = new OcgProperties();
        p.setUrl(url);
        p.setResponseCapChars(8192);
        return p;
    }

    private static HttpResponse<String> stub(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    private static void stubSend(HttpClient http, HttpResponse<String> response) throws Exception {
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static TaskModel taskWith(Map<String, Object> input) {
        TaskModel t = new TaskModel();
        t.setInputData(input);
        return t;
    }

    @Test
    void queryOperationPostsToAgentQueryEndpoint() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"citations\":[{\"source_item_id\":\"a\",\"title\":\"t1\",\"snippet\":\"s\"}]}"));
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "find foo", "max_results", 50));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/api/v1/agent/query");
        assertThat(req.getValue().method()).isEqualTo("POST");
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        // Projection keeps citations[].source_item_id but JSON-serializes the
        // whole result; the substring assertion pins both the projection and
        // the serialized-into-``result`` shape downstream INLINEs read.
        assertThat(t.getOutputData().get("result").toString()).contains("source_item_id");
        assertThat(t.getOutputData().get("operation")).isEqualTo("query");
    }

    @Test
    void getEntityOperationGetsToEntitiesEndpoint() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"id\":\"e1\",\"type\":\"message\",\"title\":\"hello\"}"));
        OcgRequestTask task = new OcgRequestTask(new OcgGetEntityOperation(), props("http://ocg.local/"), http);

        TaskModel t = taskWith(Map.of("entity_id", "e1"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        // Trailing slash on the configured URL is trimmed.
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/api/v1/entities/e1");
        assertThat(req.getValue().method()).isEqualTo("GET");
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
    }

    @Test
    void neighborhoodOperationIncludesDepthAndLimitQueryParams() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"center\":{\"id\":\"e1\"},\"edges\":[]}"));
        OcgRequestTask task = new OcgRequestTask(new OcgNeighborhoodOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("entity_id", "e1", "depth", 1, "limit", 5));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        assertThat(req.getValue().uri().toString())
                .isEqualTo("http://ocg.local/api/v1/graph/neighborhood/e1?depth=1&limit=5");
    }

    @Test
    void memoryDeleteOperationDispatchesDeleteWithQueryString() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"deleted\":true}"));
        OcgRequestTask task = new OcgRequestTask(new OcgMemoryDeleteOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("key", "k1", "agent", "agent:foo", "user", "user:bar"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        assertThat(req.getValue().method()).isEqualTo("DELETE");
        // Spring's UriComponentsBuilder encodes only characters that RFC 3986
        // reserves for query values — ``:`` is not reserved there, so it
        // stays raw. The previous hand-rolled URLEncoder.encode was over-
        // encoding to ``%3A``; OCG accepts both, but the standards-compliant
        // form is what the new builder produces.
        assertThat(req.getValue().uri().toString())
                .isEqualTo("http://ocg.local/api/v1/memories/k1?agent=agent:foo&user=user:bar");
    }

    @Test
    void non2xxResponseSurfacesAsFailedStatus() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(503, "service unavailable"));
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(t.getReasonForIncompletion()).contains("503");
    }

    @Test
    void responseLargerThanCapIsTruncatedWithSuffix() throws Exception {
        // Build a 50KB body so the post-projection JSON exceeds the 256-char
        // cap configured below. The truncation suffix must be present and
        // the result must not exceed the cap.
        StringBuilder big = new StringBuilder("{\"citations\":[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) big.append(',');
            big.append("{\"source_item_id\":\"id")
                    .append(i)
                    .append("\",\"title\":\"title")
                    .append(i)
                    .append("\"}");
        }
        big.append("]}");

        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, big.toString()));
        OcgProperties p = props("http://ocg.local");
        p.setResponseCapChars(256);
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), p, http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        String result = (String) t.getOutputData().get("result");
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        assertThat(result).hasSizeLessThanOrEqualTo(256);
        assertThat(result).endsWith("...[truncated]");
    }

    @Test
    void authorizationHeaderAttachedWhenApiKeySet() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"citations\":[]}"));
        OcgProperties p = props("http://ocg.local");
        p.setApiKey("secret-key-123");
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), p, http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        // Bearer scheme — pinning both the header name and the prefix so a
        // refactor can't silently change to e.g. ``X-API-Key`` without
        // tripping a test.
        assertThat(req.getValue().headers().firstValue("Authorization")).hasValue("Bearer secret-key-123");
    }

    @Test
    void noAuthorizationHeaderWhenApiKeyUnset() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"citations\":[]}"));
        // props(...) does not set an api key → header must be omitted so
        // unauthenticated local OCG instances keep working.
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        assertThat(req.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void disabledPropertiesYieldsFailedTask() throws Exception {
        HttpClient http = mock(HttpClient.class);
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(""), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(t.getReasonForIncompletion()).contains("not configured");
        // Importantly: no HTTP call attempted when disabled.
        verifyNoInteractions(http);
    }

    @Test
    void memorySetOperationPostsToMemoriesEndpointAndStripsAgentspanCtx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"memory_id\":\"m1\"}"));
        OcgRequestTask task = new OcgRequestTask(new OcgMemorySetOperation(), props("http://ocg.local"), http);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", "k1");
        input.put("agent", "agent:foo");
        input.put("user", "user:bar");
        input.put("string_value", "remember me");
        input.put("description", "test memory");
        // Server-side execution-token plumbing. Must NEVER be forwarded to OCG —
        // it's an internal credential glob the agent framework rides on workflow
        // inputs, not user-facing data.
        input.put("__agentspan_ctx__", "execution-token-xyz");

        TaskModel t = taskWith(input);
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/api/v1/memories");
        assertThat(req.getValue().method()).isEqualTo("POST");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(bodyOf(req.getValue()), Map.class);
        assertThat(body)
                .containsEntry("key", "k1")
                .containsEntry("agent", "agent:foo")
                .containsEntry("user", "user:bar")
                .containsEntry("string_value", "remember me")
                .containsEntry("description", "test memory")
                // The whole reason this test exists.
                .doesNotContainKey("__agentspan_ctx__");
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
    }

    @Test
    void memoryReinforceOperationPostsToReinforceEndpointWithFilteredBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"reinforced\":true}"));
        OcgRequestTask task = new OcgRequestTask(new OcgMemoryReinforceOperation(), props("http://ocg.local"), http);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", "k1");
        input.put("agent", "agent:foo");
        input.put("user", "user:bar");
        input.put("confidence_boost", 0.05);
        input.put("source_ref", "msg:42");
        // Must NOT leak.
        input.put("__agentspan_ctx__", "execution-token-xyz");
        // Must NOT be projected into the body either — pick() only takes the
        // explicitly listed fields.
        input.put("rogue_field", "nope");

        TaskModel t = taskWith(input);
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        // key is part of the URL; the body must be the picked subset.
        assertThat(req.getValue().uri().toString())
                .isEqualTo("http://ocg.local/api/v1/memories/k1/reinforce");
        assertThat(req.getValue().method()).isEqualTo("POST");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(bodyOf(req.getValue()), Map.class);
        // Allow-list: only the four picked fields are forwarded. Pinning the
        // exact key set so a future widening of pick() can't silently leak
        // server-side plumbing.
        assertThat(body).containsOnlyKeys("agent", "user", "confidence_boost", "source_ref");
        assertThat(body)
                .containsEntry("agent", "agent:foo")
                .containsEntry("user", "user:bar")
                .containsEntry("source_ref", "msg:42");
    }

    @Test
    void interruptDuringHttpSendRestoresInterruptFlag() throws Exception {
        HttpClient http = mock(HttpClient.class);
        // HttpClient.send declares ``throws IOException, InterruptedException`` —
        // doThrow on the checked InterruptedException is the canonical mocking path.
        doThrow(new InterruptedException("cancelled"))
                .when(http)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        // Clear any flag accidentally left on by an earlier test on the shared
        // test thread; we want to observe ONLY the flag the task itself sets.
        Thread.interrupted();

        task.start(null, t, null);

        // ``Thread.interrupted()`` both reads AND clears the flag — perfect for
        // a one-shot assertion. The contract under test: catching
        // InterruptedException must re-flag the current thread so Conductor's
        // executor (and anyone else up the stack) can observe the cancellation.
        boolean restored = Thread.interrupted();
        assertThat(restored)
                .as("InterruptedException must be re-flagged on the current thread")
                .isTrue();
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(t.getReasonForIncompletion()).containsIgnoringCase("interrupt");
    }

    /**
     * Drain an {@link HttpRequest}'s body publisher to a String. The JDK's
     * {@code BodyPublishers.ofString} delivers synchronously on a single
     * {@code onNext}, but we still complete a {@link CompletableFuture} on
     * {@code onComplete} and wait briefly so the helper is safe against any
     * future publisher variant.
     */
    private static String bodyOf(HttpRequest req) throws Exception {
        if (req.bodyPublisher().isEmpty()) return "";
        HttpRequest.BodyPublisher pub = req.bodyPublisher().get();
        CompletableFuture<String> done = new CompletableFuture<>();
        pub.subscribe(new Flow.Subscriber<>() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                sb.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(sb.toString());
            }
        });
        return done.get(5, TimeUnit.SECONDS);
    }
}
