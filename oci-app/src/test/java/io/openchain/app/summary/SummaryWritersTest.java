package io.openchain.app.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0022 amendment 5: both connectors, against a local stub server (no network, no paid call). */
class SummaryWritersTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ANSWER = "{\"sentences\":[{\"text\":\"It received 1000 USDT.\",\"assertionIds\":[\"as_exp\"]}]}";

    HttpServer server;
    final AtomicReference<String> path = new AtomicReference<>(), body = new AtomicReference<>(), auth = new AtomicReference<>(),
            beta = new AtomicReference<>();
    volatile int status = 200;
    volatile String reply;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization") != null ? ex.getRequestHeaders().getFirst("Authorization")
                    : ex.getRequestHeaders().getFirst("x-api-key"));
            beta.set(ex.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(out); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static String claude(String stop, String text) {
        return JSON.writeValueAsString(Map.of("id", "msg_1", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                "content", List.of(Map.of("type", "text", "text", text)), "stop_reason", stop,
                "usage", Map.of("input_tokens", 10, "output_tokens", 20)));
    }

    static String openai(String finish, String content) {
        return JSON.writeValueAsString(Map.of("id", "c1", "object", "chat.completion", "model", "gpt-test",
                "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", Map.of("role", "assistant", "content", content)))));
    }

    @Test void claudeSendsTheSchemaLowEffortAndFallbacksAndParsesTheAnswer() {
        reply = claude("end_turn", ANSWER);
        var w = new AnthropicSummaryWriter("test-key", url(), "claude-opus-5-5", Duration.ofSeconds(10));
        SummaryWriter.Draft d = w.write("SYSTEM", "Assertions of the investigation (JSON data):\n{}");
        assertThat(d.failure()).isNull();
        assertThat(d.model()).isEqualTo("claude-opus-5-5");
        assertThat(d.sentences()).singleElement().satisfies(s -> assertThat(s.assertionIds()).containsExactly("as_exp"));
        assertThat(path.get()).isEqualTo("/v1/messages");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("low");
        assertThat(sent.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(sent.path("output_config").path("format").path("schema").path("required").toString()).contains("sentences");
        assertThat(sent.path("fallbacks").asString()).isEqualTo("default");
        assertThat(beta.get()).contains("server-side-fallback-2026-07-01");
        assertThat(sent.path("system").toString()).contains("SYSTEM");
    }

    @Test void claudeRefusalsErrorsAndMalformedAnswersAreFailedDrafts() {
        var w = new AnthropicSummaryWriter("test-key", url(), "claude-opus-5-5", Duration.ofSeconds(10));
        reply = claude("refusal", "");
        assertThat(w.write("S", "U").failure()).isEqualTo("the model declined the request");
        reply = claude("max_tokens", "{\"sentences\":[");
        assertThat(w.write("S", "U").failure()).contains("stopped early");
        reply = claude("end_turn", "not json");
        assertThat(w.write("S", "U").failure()).startsWith("malformed answer");
        status = 400;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}";
        assertThat(w.write("S", "U").failure()).isEqualTo("provider error 400");
    }

    @Test void openAiCompatibleSendsTheSchemaAndTheKeyAndParsesTheAnswer() {
        reply = openai("stop", ANSWER);
        var w = new OpenAiCompatibleSummaryWriter(url() + "/v1", "sk-test", "gpt-test", OpenAiCompatibleSummaryWriter.ResponseFormat.JSON_SCHEMA,
                Duration.ofSeconds(10));
        SummaryWriter.Draft d = w.write("SYSTEM", "USER");
        assertThat(d.failure()).isNull();
        assertThat(d.model()).isEqualTo("gpt-test");
        assertThat(d.sentences()).hasSize(1);
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        assertThat(auth.get()).isEqualTo("Bearer sk-test");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_schema");
        assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(sent.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("messages").path(1).path("content").asString()).isEqualTo("USER");
    }

    @Test void openAiCompatibleJsonModeFencedAnswersRefusalsAndErrors() {
        var w = new OpenAiCompatibleSummaryWriter(url(), null, "local-model", OpenAiCompatibleSummaryWriter.ResponseFormat.JSON_OBJECT,
                Duration.ofSeconds(10));
        reply = openai("stop", "```json\n" + ANSWER + "\n```");
        assertThat(w.write("S", "U").sentences()).hasSize(1);
        assertThat(JSON.readTree(body.get()).path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(auth.get()).as("no key for a local server").isNull();
        reply = JSON.writeValueAsString(Map.of("model", "m", "choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", "", "refusal", "I can't help with that.")))));
        assertThat(w.write("S", "U").failure()).isEqualTo("the model declined the request");
        reply = openai("length", "{\"sentences\":[");
        assertThat(w.write("S", "U").failure()).contains("stopped early");
        status = 429;
        reply = "{}";
        assertThat(w.write("S", "U").failure()).isEqualTo("provider error 429");
    }

    @Test void aRemoteBaseUrlMustUseHttps() {
        assertThatThrownBy(() -> new OpenAiCompatibleSummaryWriter("http://api.example.com/v1", "k", "m", null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OpenAiCompatibleSummaryWriter.isLocal(URI.create("http://localhost:11434/v1"))).isTrue();
        assertThat(OpenAiCompatibleSummaryWriter.isLocal(URI.create("http://10.0.0.5/v1"))).isFalse();
    }
}
