package com.nxr.platform.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekAiClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public DeepSeekAiClient(
        ObjectMapper objectMapper,
        @Value("${nxr.ai.deepseek-key:}") String apiKey,
        @Value("${nxr.ai.deepseek-base-url:https://api.deepseek.com}") String baseUrl,
        @Value("${nxr.ai.deepseek-model:deepseek-chat}") String model
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.apiKey = clean(apiKey);
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = clean(model).isBlank() ? "deepseek-chat" : clean(model);
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    public Generation generate(List<Map<String, String>> messages) {
        requireEnabled();
        HttpResponse<InputStream> response = send(payload(messages, false));
        try (InputStream body = response.body()) {
            ensureSuccess(response.statusCode(), body);
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new AiProviderException("DeepSeek returned no usable content");
            }
            return new Generation(content, model);
        } catch (IOException exc) {
            throw new AiProviderException("Unable to read DeepSeek response", exc);
        }
    }

    public Generation stream(List<Map<String, String>> messages, Consumer<String> chunkConsumer) {
        requireEnabled();
        HttpResponse<InputStream> response = send(payload(messages, true));
        StringBuilder completeText = new StringBuilder();
        try (InputStream body = response.body()) {
            ensureSuccess(response.statusCode(), body);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode event = objectMapper.readTree(data);
                    String chunk = event.path("choices").path(0).path("delta").path("content").asText("");
                    if (!chunk.isEmpty()) {
                        completeText.append(chunk);
                        chunkConsumer.accept(chunk);
                    }
                }
            }
        } catch (IOException exc) {
            throw new AiProviderException("Unable to read DeepSeek stream", exc);
        }
        if (completeText.isEmpty()) {
            throw new AiProviderException("DeepSeek returned no usable stream content");
        }
        return new Generation(completeText.toString(), model);
    }

    private Map<String, Object> payload(List<Map<String, String>> messages, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 700);
        payload.put("stream", stream);
        return payload;
    }

    private HttpResponse<InputStream> send(Map<String, Object> payload) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException exc) {
            throw new AiProviderException("Unable to encode DeepSeek request", exc);
        }

        HttpResponse<InputStream> lastResponse = null;
        for (String endpoint : List.of("/chat/completions", "/v1/chat/completions")) {
            if (lastResponse != null) {
                closeQuietly(lastResponse.body());
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
            try {
                lastResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                throw new AiProviderException("DeepSeek request was interrupted", exc);
            } catch (IOException exc) {
                throw new AiProviderException("Unable to connect to DeepSeek", exc);
            }
            if (lastResponse.statusCode() != 404) {
                return lastResponse;
            }
        }
        return lastResponse;
    }

    private void ensureSuccess(int statusCode, InputStream body) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String responseText = new String(body.readNBytes(600), StandardCharsets.UTF_8);
        throw new AiProviderException("DeepSeek request failed with HTTP " + statusCode + ": " + responseText);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // The next endpoint attempt is still safe after a close failure.
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String normalized = clean(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "https://api.deepseek.com" : normalized;
    }

    public record Generation(String content, String model) {
    }

    public static class AiProviderException extends RuntimeException {
        public AiProviderException(String message) {
            super(message);
        }

        public AiProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
