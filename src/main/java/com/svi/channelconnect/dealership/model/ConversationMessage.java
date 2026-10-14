package com.svi.channelconnect.dealership.model;

public record ConversationMessage(long sequence, String id, String direction, String channel, String externalId,
		String content, String deliveryStatus, long occurredAt) {
}
