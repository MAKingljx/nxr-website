package com.nxr.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeepSeekAiClientTest {

    private HttpServer server;
    private DeepSeekAiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::respond);
        server.start();
        client = new DeepSeekAiClient(
            new ObjectMapper(),
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "deepseek-test"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void supportsJsonAndRealSseGenerationPaths() {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "user", "content", "Pikachu")
        );

        DeepSeekAiClient.Generation generated = client.generate(messages);
        List<String> chunks = new ArrayList<>();
        DeepSeekAiClient.Generation streamed = client.stream(messages, chunks::add);

        assertThat(generated.content()).isEqualTo("First paragraph.\n\nSecond paragraph.");
        assertThat(generated.model()).isEqualTo("deepseek-test");
        assertThat(chunks).containsExactly("First paragraph.", "\n\nSecond paragraph.");
        assertThat(streamed.content()).isEqualTo("First paragraph.\n\nSecond paragraph.");
    }

    private void respond(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
        if (requestBody.contains("\"stream\":true")) {
            write(
                exchange,
                "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"First paragraph.\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\nSecond paragraph.\"}}]}\n\n"
                    + "data: [DONE]\n\n"
            );
            return;
        }
        write(
            exchange,
            "application/json",
            "{\"choices\":[{\"message\":{\"content\":\"First paragraph.\\n\\nSecond paragraph.\"}}]}"
        );
    }

    private void write(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
