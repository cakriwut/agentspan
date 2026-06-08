/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.netflix.conductor.model.TaskModel;

/**
 * Unit tests for {@link OcgRequestTask}.
 *
 * <p>Each operation is exercised against a mocked {@link HttpClient} to pin the
 * URL/method contract with the OCG service and the projection + capping
 * behaviour.</p>
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
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "find foo", "max_results", 50));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/agent/query");
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
        OcgRequestTask task =
                new OcgRequestTask("OCG_GET_ENTITY", OcgRequestTask.OP_GET_ENTITY, props("http://ocg.local/"), http);

        TaskModel t = taskWith(Map.of("entity_id", "e1"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
        // Trailing slash on the configured URL is trimmed.
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/entities/e1");
        assertThat(req.getValue().method()).isEqualTo("GET");
        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
    }

    @Test
    void neighborhoodOperationIncludesDepthAndLimitQueryParams() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"center\":{\"id\":\"e1\"},\"edges\":[]}"));
        OcgRequestTask task =
                new OcgRequestTask("OCG_NEIGHBORHOOD", OcgRequestTask.OP_NEIGHBORHOOD, props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("entity_id", "e1", "depth", 1, "limit", 5));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
        assertThat(req.getValue().uri().toString()).isEqualTo("http://ocg.local/graph/neighborhood/e1?depth=1&limit=5");
    }

    @Test
    void memoryDeleteOperationDispatchesDeleteWithQueryString() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(200, "{\"deleted\":true}"));
        OcgRequestTask task = new OcgRequestTask(
                "OCG_MEMORY_DELETE", OcgRequestTask.OP_MEMORY_DELETE, props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("key", "k1", "agent", "agent:foo", "user", "user:bar"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
        assertThat(req.getValue().method()).isEqualTo("DELETE");
        // URL-encoded query params; agent and user appear in declared order.
        assertThat(req.getValue().uri().toString())
                .isEqualTo("http://ocg.local/memories/k1?agent=agent%3Afoo&user=user%3Abar");
    }

    @Test
    void non2xxResponseSurfacesAsFailedStatus() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stubSend(http, stub(503, "service unavailable"));
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, props("http://ocg.local"), http);

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
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, p, http);

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
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, p, http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
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
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, props("http://ocg.local"), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(req.capture(), any());
        assertThat(req.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void disabledPropertiesYieldsFailedTask() throws Exception {
        HttpClient http = mock(HttpClient.class);
        OcgRequestTask task = new OcgRequestTask("OCG_QUERY", OcgRequestTask.OP_QUERY, props(""), http);

        TaskModel t = taskWith(Map.of("query", "x"));
        task.start(null, t, null);

        assertThat(t.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(t.getReasonForIncompletion()).contains("not configured");
        // Importantly: no HTTP call attempted when disabled.
        org.mockito.Mockito.verifyNoInteractions(http);
    }
}
