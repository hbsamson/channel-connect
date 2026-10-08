package com.svi.channelconnect.client;

import tools.jackson.databind.JsonNode;
import com.svi.channelconnect.config.MessengerProperties;
import com.svi.channelconnect.dto.OutgoingMessageDTO;
import com.svi.channelconnect.dto.SendMessageResponseDTO;
import com.svi.channelconnect.exception.ProviderException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MessengerSendClient {
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
                throw new ProviderException("invalid_response");
            }
            return new SendMessageResponseDTO("SENT", messageId);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String category = status == 401 || status == 403 ? "authentication"
                    : status == 429 ? "rate_limit" : status >= 500 ? "server" : "provider";
            throw new ProviderException(category);
        } catch (RestClientException exception) {
            throw new ProviderException("transport");
        }
    }
}
