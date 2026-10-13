package com.svi.channelconnect.client;

import tools.jackson.databind.JsonNode;
import com.svi.channelconnect.config.OpenAiProperties;
import com.svi.channelconnect.exception.ProviderException;
import com.svi.channelconnect.persistence.MessageDirection;
import com.svi.channelconnect.persistence.StoredMessage;
import com.svi.channelconnect.service.AiDiagnosticService;
import com.svi.channelconnect.service.MessageProcessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class OpenAiResponsesClient implements MessageProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiResponsesClient.class);
    public static final String NO_SOURCE_REPLY = "I don't have enough information in the available documents to answer that.";
    private static final String INSTRUCTIONS = "You are a concise Messenger assistant. Use file_search for the "
            + "current user question. Answer only with facts supported by the retrieved documents. "
            + "If the documents do not answer the question, say that the information is unavailable. "
            + "Do not include file names or citations in the Messenger reply.";
    private static final String GENERAL_INSTRUCTIONS = "You are a concise Messenger assistant. "
            + "Answer the user's question helpfully. No reference documents are currently available. "
            + "Do not claim to have consulted documents or invent organization-specific information.";

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
            logResponse(response);
            if (response == null || !response.isObject()) {
                throw new ProviderException("malformed_response");
            }
            responseId = response.path("id").asText(null);
            LOGGER.info("OpenAI processing conversation={} stage=response_parsing", conversationId);
            validateResponse(response);
            boolean hasSources = hasSearchResults(response);
            boolean canAnswer = !hasVectorStore() || hasSources;
            String assistantText = extractText(response);
            LOGGER.info("OpenAI text extraction conversation={} assistant_text_present={} assistant_text_chars={} source_results_present={}",
                    conversationId, !assistantText.isBlank(), assistantText.length(), hasSources);
            String reply = canAnswer ? assistantText : NO_SOURCE_REPLY;
            if (reply == null || reply.isBlank()) {
                throw new ProviderException("empty_reply");
            }
            LOGGER.info("OpenAI processing conversation={} stage=diagnostic_persistence", conversationId);
            diagnostics.record(conversationId, responseId, properties.model(),
                    canAnswer ? "COMPLETED" : "NO_SOURCE", null, elapsedMillis(started));
            LOGGER.info("OpenAI processing conversation={} stage=generation_completed", conversationId);
            return reply;
        } catch (RestClientResponseException exception) {
            String category = classify(exception.getStatusCode().value());
            diagnostics.record(conversationId, responseId, properties.model(),
                    "FAILED", category, elapsedMillis(started));
            LOGGER.warn("OpenAI request rejected conversation={} http_status={} category={}",
                    conversationId, exception.getStatusCode().value(), category);
            throw new ProviderException("openai", category, exception);
        } catch (RestClientException exception) {
            String category = "transport";
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpMessageConversionException) {
                    category = "malformed_response";
                    break;
                }
            }
            diagnostics.record(conversationId, responseId, properties.model(),
                    "FAILED", category, elapsedMillis(started));
            throw new ProviderException("openai", category, exception);
        } catch (ProviderException exception) {
            diagnostics.record(conversationId, responseId, properties.model(),
                    "incomplete_response".equals(exception.getCategory()) ? "INCOMPLETE" : "FAILED",
                    exception.getCategory(), elapsedMillis(started));
            throw exception;
        }
    }

    private Map<String, Object> requestBody(List<StoredMessage> history) {
        List<Map<String, String>> input = new ArrayList<>();
        List<StoredMessage> selectedHistory = history.subList(Math.max(0, history.size() - properties.historyLimit()), history.size());
        for (StoredMessage message : selectedHistory) {
            input.add(Map.of("role", message.getDirection() == MessageDirection.INCOMING ? "user" : "assistant",
                    "content", message.getContent()));
        }
        Map<String, Object> body = new HashMap<>(Map.of(
                "model", properties.model(),
                "instructions", hasVectorStore() ? INSTRUCTIONS : GENERAL_INSTRUCTIONS,
                "input", input,
                "max_output_tokens", properties.maxOutputTokens(),
                "store", false));
        if (properties.reasoningEffort() != null && !properties.reasoningEffort().isBlank()) {
            body.put("reasoning", Map.of("effort", properties.reasoningEffort()));
        }
        LOGGER.info("OpenAI request input_messages={} max_output_tokens={} reasoning_effort={} file_search_enabled={}",
                input.size(), properties.maxOutputTokens(), properties.reasoningEffort(), hasVectorStore());
        if (hasVectorStore()) {
            body.put("tools", List.of(Map.of("type", "file_search",
                    "vector_store_ids", List.of(properties.vectorStoreId()),
                    "max_num_results", 3)));
            body.put("include", List.of("file_search_call.results"));
        }
        return body;
    }

    private boolean hasVectorStore() {
        return properties.vectorStoreId() != null && !properties.vectorStoreId().isBlank();
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
            if (!"message".equals(item.path("type").asText())
                    || !"assistant".equals(item.path("role").asText())) {
                continue;
            }
            if (!item.path("content").isArray()) {
                throw new ProviderException("malformed_response");
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) {
                    if (!part.path("text").isString()) {
                        throw new ProviderException("malformed_response");
                    }
                    String value = part.path("text").asText().trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value);
                }
            }
        }
        return text.toString().trim();
    }

    private void validateResponse(JsonNode response) {
        switch (response.path("status").asText()) {
            case "incomplete" -> throw new ProviderException("incomplete_response");
            case "failed" -> throw new ProviderException("failed_response");
            case "queued", "in_progress", "cancelled" -> throw new ProviderException("unexpected_response_status");
            case "completed" -> {
                if (!response.path("output").isArray()
                        || (response.hasNonNull("error"))) {
                    throw new ProviderException("malformed_response");
                }
                for (JsonNode item : response.path("output")) {
                    if (!item.isObject() || !item.path("type").isString()) {
                        throw new ProviderException("malformed_response");
                    }
                }
            }
            default -> throw new ProviderException("malformed_response");
        }
    }

    private void logResponse(JsonNode response) {
        JsonNode root = response;
        if (root == null) {
            LOGGER.info("OpenAI response status=missing output_types=[] input_tokens=unknown output_tokens=unknown reasoning_tokens=unknown incomplete_reason=none");
            return;
        }
        List<String> types = new ArrayList<>();
        if (root.path("output").isArray()) {
            for (JsonNode item : root.path("output")) {
                String type = safeLabel(item.path("type"), List.of("message", "reasoning", "file_search_call",
                        "web_search_call", "function_call", "computer_call", "code_interpreter_call", "mcp_call"));
                if (!types.contains(type)) {
                    types.add(type);
                }
            }
        }
        JsonNode usage = root.path("usage");
        LOGGER.info("OpenAI response status={} output_types={} input_tokens={} output_tokens={} reasoning_tokens={} incomplete_reason={}",
                safeLabel(root.path("status"), List.of("completed", "incomplete", "failed", "queued", "in_progress", "cancelled")),
                types, tokenCount(usage.path("input_tokens")), tokenCount(usage.path("output_tokens")),
                tokenCount(usage.path("output_tokens_details").path("reasoning_tokens")),
                safeLabel(root.path("incomplete_details").path("reason"), List.of("max_output_tokens", "content_filter")));
    }

    private String safeLabel(JsonNode value, List<String> allowed) {
        return allowed.contains(value.asText()) ? value.asText() : "unknown";
    }

    private String tokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0 ? Long.toString(value.asLong()) : "unknown";
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
