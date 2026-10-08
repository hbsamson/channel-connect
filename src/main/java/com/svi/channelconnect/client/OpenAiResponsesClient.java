package com.svi.channelconnect.client;

import tools.jackson.databind.JsonNode;
import com.svi.channelconnect.config.OpenAiProperties;
import com.svi.channelconnect.exception.ProviderException;
import com.svi.channelconnect.persistence.MessageDirection;
import com.svi.channelconnect.persistence.StoredMessage;
import com.svi.channelconnect.service.AiDiagnosticService;
import com.svi.channelconnect.service.MessageProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class OpenAiResponsesClient implements MessageProcessor {
    public static final String NO_SOURCE_REPLY = "I don't have enough information in the available documents to answer that.";
    private static final String INSTRUCTIONS = "You are a concise Messenger assistant. Use file_search for the "
            + "current user question. Answer only with facts supported by the retrieved documents. "
            + "If the documents do not answer the question, say that the information is unavailable. "
            + "Do not include file names or citations in the Messenger reply.";

    private final RestClient client;
    private final OpenAiProperties properties;
    private final AiDiagnosticService diagnostics;

    public OpenAiResponsesClient(RestClient.Builder builder, OpenAiProperties properties,
                                 AiDiagnosticService diagnostics) {
        this.client = builder.baseUrl("https://api.openai.com").build();
        this.properties = properties;
        this.diagnostics = diagnostics;
    }

    @Override
    public String generateReply(UUID conversationId, List<StoredMessage> history) {
        long started = System.nanoTime();
        String responseId = null;
        try {
            JsonNode response = client.post()
                    .uri("/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(history))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"completed".equals(response.path("status").asText())) {
                throw new ProviderException("invalid_response");
            }
            responseId = response.path("id").asText(null);
            boolean hasSources = hasSearchResults(response);
            String reply = hasSources ? extractText(response) : NO_SOURCE_REPLY;
            if (reply == null || reply.isBlank()) {
                throw new ProviderException("empty_reply");
            }
            diagnostics.record(conversationId, responseId, properties.model(),
                    hasSources ? "COMPLETED" : "NO_SOURCE", null, elapsedMillis(started));
            return reply;
        } catch (RestClientResponseException exception) {
            String category = classify(exception.getStatusCode().value());
            diagnostics.record(conversationId, responseId, properties.model(),
                    "FAILED", category, elapsedMillis(started));
            throw new ProviderException(category);
        } catch (RestClientException exception) {
            diagnostics.record(conversationId, responseId, properties.model(),
                    "FAILED", "transport", elapsedMillis(started));
            throw new ProviderException("transport");
        } catch (ProviderException exception) {
            diagnostics.record(conversationId, responseId, properties.model(),
                    "FAILED", exception.getCategory(), elapsedMillis(started));
            throw exception;
        }
    }

    private Map<String, Object> requestBody(List<StoredMessage> history) {
        List<Map<String, String>> input = new ArrayList<>();
        for (StoredMessage message : history) {
            input.add(Map.of("role", message.getDirection() == MessageDirection.INCOMING ? "user" : "assistant",
                    "content", message.getContent()));
        }
        return Map.of(
                "model", properties.model(),
                "instructions", INSTRUCTIONS,
                "input", input,
                "tools", List.of(Map.of("type", "file_search",
                        "vector_store_ids", List.of(properties.vectorStoreId()),
                        "max_num_results", 3)),
                "include", List.of("file_search_call.results"),
                "max_output_tokens", 400,
                "store", false);
    }

    private boolean hasSearchResults(JsonNode response) {
        for (JsonNode item : response.path("output")) {
            if ("file_search_call".equals(item.path("type").asText())
                    && item.path("results").isArray() && !item.path("results").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String extractText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(part.path("text").asText());
                }
            }
        }
        return text.toString().trim();
    }

    private String classify(int status) {
        if (status == 401 || status == 403) {
            return "authentication";
        }
        if (status == 429) {
            return "rate_limit";
        }
        return status >= 500 ? "server" : "provider";
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
