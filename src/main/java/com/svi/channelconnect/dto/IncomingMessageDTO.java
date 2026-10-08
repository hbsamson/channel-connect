package com.svi.channelconnect.dto;

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
