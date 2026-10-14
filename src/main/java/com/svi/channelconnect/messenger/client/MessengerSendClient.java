package com.svi.channelconnect.messenger.client;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.exception.ProviderException;

@Component
public class MessengerSendClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessengerSendClient.class);
    private static final JsonMapper ERROR_MAPPER = JsonMapper.builder().build();
    private final RestClient client;
    private final MessengerProperties properties;

    public MessengerSendClient(RestClient.Builder builder, MessengerProperties properties) {
        this.client = builder.baseUrl("https://graph.facebook.com").build();
        this.properties = properties;
    }

    public SendMessageResponseDTO send(OutgoingMessageDTO message) {
        if (message.recipientId() == null || message.recipientId().isBlank()
                || message.content() == null || message.content().isBlank()) {
            throw new IllegalArgumentException("Recipient and content are required");
        }
        try {
            JsonNode response = client.post()
                    .uri("/{version}/{pageId}/messages", properties.graphApiVersion(), properties.pageId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.pageAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("recipient", Map.of("id", message.recipientId()),
                            "message_type", "RESPONSE",
                            "message", Map.of("text", message.content())))
                    .retrieve()
                    .body(JsonNode.class);
            String messageId = response == null ? null : response.path("message_id").asText(null);
            if (messageId == null || messageId.isBlank()) {
                throw new ProviderException("messenger", "invalid_response", null);
            }
            return new SendMessageResponseDTO("SENT", messageId);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String category = status == 401 || status == 403 ? "authentication"
                    : status == 429 ? "rate_limit" : status >= 500 ? "server" : "provider";
            JsonNode error = null;
            try {
                error = ERROR_MAPPER.readTree(exception.getResponseBodyAsString()).path("error");
            } catch (RuntimeException ignored) {
                // Non-JSON provider errors still retain their HTTP status and original cause.
            }
            LOGGER.warn("Messenger Send API rejected reply http_status={} error_type={} error_code={} error_subcode={} error_message={} fbtrace_id={}",
                    status, textField(error, "type", message), numericField(error, "code"),
                    numericField(error, "error_subcode"), textField(error, "message", message),
                    textField(error, "fbtrace_id", message));
            throw new ProviderException("messenger", category, exception);
        } catch (RestClientException exception) {
            throw new ProviderException("messenger", "transport", exception);
        }
    }

    private String numericField(JsonNode error, String field) {
        return error != null && error.path(field).isIntegralNumber()
                ? Long.toString(error.path(field).asLong()) : "unknown";
    }

    private String textField(JsonNode error, String field, OutgoingMessageDTO outgoing) {
        if (error == null || !error.path(field).isString()) {
            return "unknown";
        }
        String value = error.path(field).asText();
        // Meta may echo request values in an error. Redact before normalizing log lines.
        for (String sensitive : new String[] {properties.pageAccessToken(), properties.appSecret(),
                properties.verifyToken(), outgoing.content(), outgoing.recipientId()}) {
            if (sensitive != null && !sensitive.isBlank()) {
                value = value.replace(sensitive, "[REDACTED]");
            }
        }
        value = value.replaceAll("(?i)authorization\\s*[:=]\\s*[^\\r\\n]+", "[REDACTED_HEADER]")
                .replaceAll("(?i)bearer\\s+[^\\s\"',;]+", "[REDACTED_TOKEN]")
                .replaceAll("(?i)access_token\\s*[=:]\\s*[^\\s&\"',;]+", "access_token=[REDACTED]");
        return value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
    }
}
