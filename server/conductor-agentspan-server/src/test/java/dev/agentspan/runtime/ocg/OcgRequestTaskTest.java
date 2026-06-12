/*
 * Copyright (c) 2025 AgentSpan
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */

package dev.agentspan.runtime.ocg;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import dev.agentspan.runtime.ocg.operation.OcgQueryOperation;

/**
 * Per-call OCG instance resolution: the tool-bound instance
 * ({@code __ocg_url} / {@code __ocg_auth} task inputs) is the only
 * instance — there is no server-side default. A task dispatched without
 * {@code __ocg_url} fails fast.
 */
class OcgRequestTaskTest {

    private HttpClient httpClient;
    private HttpResponse<String> response;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        httpClient = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"citations\":[]}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private static OcgProperties props() {
        return new OcgProperties();
    }

    private static TaskModel taskWithInput(Map<String, Object> input) {
        TaskModel task = new TaskModel();
        task.setInputData(new HashMap<>(input));
        return task;
    }

    private HttpRequest sentRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void perToolUrlIsUsed() throws Exception {
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, null);
        TaskModel model = taskWithInput(Map.of("query", "hello", "__ocg_url", "https://ca.ocg.example.com"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        assertThat(sentRequest().uri().toString()).startsWith("https://ca.ocg.example.com/api/v1/");
    }

    @Test
    void failsFastWithoutBoundInstance() {
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, null);
        TaskModel model = taskWithInput(Map.of("query", "hello"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(model.getReasonForIncompletion()).contains("no OCG instance").contains("url=");
        verifyNoInteractions(httpClient);
    }

    @Test
    void preResolvedAuthHeaderIsSentVerbatim() throws Exception {
        // Embedded mode: the host already substituted ${workflow.secrets.NAME},
        // so __ocg_auth arrives fully resolved.
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, null);
        TaskModel model = taskWithInput(Map.of(
                "query", "hello",
                "__ocg_url", "https://us.ocg.example.com",
                "__ocg_auth", "Bearer resolved-us-secret"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        assertThat(sentRequest().headers().firstValue("Authorization")).hasValue("Bearer resolved-us-secret");
    }

    @Test
    void placeholderAuthIsResolvedThroughCredentialResolver() throws Exception {
        OcgCredentialResolver resolver = (value, ctx) -> value.replace("#{OCG_US_KEY}", "us-secret");
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, resolver);
        TaskModel model = taskWithInput(Map.of(
                "query", "hello",
                "__ocg_url", "https://us.ocg.example.com",
                "__ocg_auth", "Bearer #{OCG_US_KEY}",
                "__agentspan_ctx__", Map.of("execution_token", "tok")));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        assertThat(sentRequest().headers().firstValue("Authorization")).hasValue("Bearer us-secret");
    }

    @Test
    void unresolvablePlaceholderFailsTheTask() {
        // Resolver returns null (unknown credential / invalid token): the task
        // must fail rather than send the placeholder as a bearer token.
        OcgCredentialResolver resolver = (value, ctx) -> null;
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, resolver);
        TaskModel model = taskWithInput(Map.of(
                "query", "hello",
                "__ocg_url", "https://us.ocg.example.com",
                "__ocg_auth", "Bearer #{OCG_US_KEY}"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.FAILED);
        assertThat(model.getReasonForIncompletion()).contains("credential");
        verifyNoInteractions(httpClient);
    }

    @Test
    void noAuthHeaderWhenNoPerToolCredential() throws Exception {
        // No credential bound → unauthenticated call; there is no server-side
        // default key to silently attach.
        OcgRequestTask task = new OcgRequestTask(new OcgQueryOperation(), props(), httpClient, null);
        TaskModel model = taskWithInput(Map.of("query", "hello", "__ocg_url", "https://us.ocg.example.com"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        assertThat(sentRequest().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void reservedInputsAreNotForwardedToTheOcgApi() throws Exception {
        // OcgMemorySetOperation posts the whole input map as the request body —
        // the instance-binding keys must never leak into it.
        OcgRequestTask task = new OcgRequestTask(
                new dev.agentspan.runtime.ocg.operation.OcgMemorySetOperation(), props(), httpClient, null);
        TaskModel model = taskWithInput(Map.of(
                "key",
                "k",
                "agent",
                "a",
                "user",
                "u",
                "string_value",
                "v",
                "description",
                "d",
                "__ocg_url",
                "https://us.ocg.example.com",
                "__ocg_auth",
                "Bearer resolved"));

        task.start(new WorkflowModel(), model, null);

        assertThat(model.getStatus()).isEqualTo(TaskModel.Status.COMPLETED);
        HttpRequest sent = sentRequest();
        String body = sent.bodyPublisher()
                .map(p -> {
                    var collector = java.net.http.HttpResponse.BodySubscribers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8);
                    p.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                            collector.onSubscribe(s);
                            s.request(Long.MAX_VALUE);
                        }

                        public void onNext(java.nio.ByteBuffer item) {
                            collector.onNext(java.util.List.of(item));
                        }

                        public void onError(Throwable t) {
                            collector.onError(t);
                        }

                        public void onComplete() {
                            collector.onComplete();
                        }
                    });
                    return collector.getBody().toCompletableFuture().join();
                })
                .orElse("");
        assertThat(body).doesNotContain("__ocg_url").doesNotContain("__ocg_auth");
        assertThat(body).contains("\"key\":\"k\"");
    }
}
