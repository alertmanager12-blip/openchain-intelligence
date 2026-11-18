package io.openchain.app.summary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Claude through the official Java SDK (ADR-0022 amendment 5): strict structured output (the response schema of
 * {@link SummaryPrompt}), low effort, server-side refusal fallbacks. A refusal, an API error or an unexpected stop is a
 * failed draft with its reason, never an exception to the caller.
 */
public final class AnthropicSummaryWriter implements SummaryWriter {
    private final AnthropicClient client;
    private final String model;

    public AnthropicSummaryWriter(String apiKey, String baseUrl, String model, Duration timeout) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(2);
        if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
        this.client = b.build();
        this.model = model;
    }

    @Override public Draft write(String system, String user) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        SummaryPrompt.schema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .addUserMessage(user)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schema.build()).build()).build())
                // a request a safety classifier declines is re-run server-side on the recommended fallback model
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        Message m;
        try {
            m = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            return Draft.failed(model, "provider error " + e.statusCode());
        } catch (AnthropicException e) {
            return Draft.failed(model, "provider unreachable: " + e.getClass().getSimpleName());
        }
        String answered = m.model().asString();
        StopReason stop = m.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) return Draft.failed(answered, "the model declined the request");
        if (!StopReason.END_TURN.equals(stop)) return Draft.failed(answered, "the answer stopped early (" + stop + ")");
        String text = m.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).collect(Collectors.joining());
        try {
            return new Draft(SummaryPrompt.parse(text), answered, null);
        } catch (IllegalArgumentException e) {
            return Draft.failed(answered, "malformed answer: " + e.getMessage());
        }
    }

}
