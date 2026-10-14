package com.svi.channelconnect.whatsapp.model;

import java.time.Instant;

public record InboundMessage(
		String messageId,
		String conversationId,
		String senderWaId,
		String text,
		Instant receivedAt
) {
}
