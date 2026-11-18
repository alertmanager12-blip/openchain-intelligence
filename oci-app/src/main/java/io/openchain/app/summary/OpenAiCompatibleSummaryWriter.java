package io.openchain.app.summary;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A provider exposing an OpenAI-compatible chat-completions endpoint (ADR-0022 amendment 5): ChatGPT, and providers or
 * local servers (Ollama, vLLM) that offer the same API, configured by base URL, model and key. Structured output is asked
 * as a JSON schema, or as plain JSON mode for providers without schema support; either way the answer is parsed and
 * checked on the server. Errors are failed drafts, never exceptions to the caller.
 */
public final class OpenAiCompatibleSummaryWriter implements SummaryWriter {
    public enum ResponseFormat { JSON_SCHEMA, JSON_OBJECT }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final ResponseFormat format;
    private final Duration timeout;

    public OpenAiCompatibleSummaryWriter(String baseUrl, String apiKey, String model, ResponseFormat format, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("base URL is required");
        String b = baseUrl.strip();
        this.endpoint = URI.create(b.endsWith("/") ? b + "chat/completions" : b + "/chat/completions");
        if (!"https".equals(endpoint.getScheme()) && !isLocal(endpoint)) {
            throw new IllegalArgumentException("base URL must use https (http only for a local server)");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.format = format == null ? ResponseFormat.JSON_SCHEMA : format;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static boolean isLocal(URI u) {
        String h = u.getHost();
        return "http".equals(u.getScheme()) && h != null && (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]")
                || h.endsWith(".localhost") || h.equals("host.docker.internal"));
    }

    @Override public Draft write(String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("response_format", format == ResponseFormat.JSON_SCHEMA
                ? Map.of("type", "json_schema", "json_schema", Map.of("name", "summary", "strict", true, "schema", SummaryPrompt.schema()))
                : Map.of("type", "json_object"));
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Draft.failed(model, "provider unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Draft.failed(model, "interrupted");
        }
        if (resp.statusCode() / 100 != 2) return Draft.failed(model, "provider error " + resp.statusCode());
        JsonNode root;
        try {
            root = JSON.readTree(resp.body());
        } catch (RuntimeException e) {
            return Draft.failed(model, "malformed provider response");
        }
        String answered = root.path("model").isString() ? root.path("model").asString() : model;
        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").isString() ? choice.path("finish_reason").asString() : "";
        JsonNode message = choice.path("message");
        if (message.path("refusal").isString() && !message.path("refusal").asString().isBlank()) {
            return Draft.failed(answered, "the model declined the request");
        }
        if (!finish.equals("stop")) return Draft.failed(answered, "the answer stopped early (" + (finish.isEmpty() ? "unknown" : finish) + ")");
        try {
            return new Draft(SummaryPrompt.parse(message.path("content").isString() ? message.path("content").asString() : null), answered, null);
        } catch (IllegalArgumentException e) {
            return Draft.failed(answered, "malformed answer: " + e.getMessage());
        }
    }
}
