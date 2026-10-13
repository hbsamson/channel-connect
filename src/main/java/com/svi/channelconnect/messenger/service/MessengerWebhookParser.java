package com.svi.channelconnect.messenger.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.MalformedWebhookException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MessengerWebhookParser {
    private final ObjectMapper mapper;
    private final MessengerProperties properties;
    private final Clock clock;

    public MessengerWebhookParser(ObjectMapper mapper, MessengerProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public List<IncomingMessageDTO> parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (JacksonException exception) {
            throw new MalformedWebhookException();
        } catch (Exception exception) {
            throw new MalformedWebhookException();
        }
        if (root == null || !root.isObject()) {
            throw new MalformedWebhookException();
        }
        if (!"page".equals(root.path("object").asText())) {
            return List.of();
        }
        JsonNode entries = root.path("entry");
        if (!entries.isArray()) {
            throw new MalformedWebhookException();
        }
        List<IncomingMessageDTO> messages = new ArrayList<>();
        for (JsonNode entry : entries) {
            String pageId = entry.path("id").asText();
            if (!properties.pageId().equals(pageId)) {
                continue;
            }
            for (JsonNode event : entry.path("messaging")) {
                JsonNode message = event.path("message");
                String sender = event.path("sender").path("id").asText();
                String recipient = event.path("recipient").path("id").asText();
                String mid = message.path("mid").asText();
                String content = message.path("text").asText();
                if (message.path("is_echo").asBoolean(false)
                        || properties.pageId().equals(sender)
                        || !properties.pageId().equals(recipient)
                        || sender.isBlank() || mid.isBlank() || content.isBlank()) {
                    continue;
                }
                long timestamp = event.path("timestamp").asLong(0);
                Instant eventTime = timestamp > 0 ? Instant.ofEpochMilli(timestamp) : clock.instant();
                messages.add(new IncomingMessageDTO("MESSENGER", pageId, mid, sender,
                        recipient, content, "TEXT", eventTime));
            }
        }
        return messages;
    }
}
