package com.svi.channelconnect.whatsapp.messaging.messenger.dto;

import java.time.Instant;

public record IncomingMessageDTO(
        String channel,
        String pageId,
        String externalMessageId,
        String senderId,
        String recipientId,
        String content,
        String messageType,
        Instant timestamp) {
}
