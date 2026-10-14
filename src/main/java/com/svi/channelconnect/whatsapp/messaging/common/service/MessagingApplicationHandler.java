package com.svi.channelconnect.whatsapp.messaging.common.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.svi.channelconnect.whatsapp.messaging.ai.client.AiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiResponse;
import com.svi.channelconnect.whatsapp.messaging.ai.exception.AiClientException;
import com.svi.channelconnect.whatsapp.messaging.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.whatsapp.messaging.common.exception.ExternalFailureCategory;

/** Routes authenticated channel workflows to registered platform adapters. */
@Service
public class MessagingApplicationHandler {
	private final Map<String, MessagingChannel> channels;
	private final AiClient aiClient;

	public MessagingApplicationHandler(List<MessagingChannel> adapters, AiClient aiClient) {
		Map<String, MessagingChannel> registered = new HashMap<>();
		for (MessagingChannel adapter : adapters) {
			Assert.hasText(adapter.getChannelName(), "Channel name is required");
			if (registered.putIfAbsent(adapter.getChannelName(), adapter) != null) {
				throw new IllegalStateException("Duplicate messaging channel: " + adapter.getChannelName());
			}
		}
		this.channels = Map.copyOf(registered);
		this.aiClient = aiClient;
	}

	public SendMessageResponseDTO sendMessage(String channel, OutgoingMessageDTO message) {
		MessagingChannel adapter = requireChannel(channel);
		Assert.notNull(message, "Message is required");
		Assert.hasText(message.recipientId(), "Recipient is required");
		Assert.hasText(message.content(), "Message content is required");
		return adapter.sendMessage(message);
	}

	public SendMessageResponseDTO reply(String channel, String conversationId, String senderId, String text) {
		requireChannel(channel);
		Assert.hasText(conversationId, "Conversation is required");
		Assert.hasText(senderId, "Sender is required");
		Assert.hasText(text, "Message content is required");
		AiResponse response = aiClient.generateResponse(new AiRequest(conversationId, text));
		if (response == null || !StringUtils.hasText(response.text())) {
			throw new AiClientException(ExternalFailureCategory.MALFORMED_RESPONSE,
					"AI provider returned an empty response", null);
		}
		return sendMessage(channel, new OutgoingMessageDTO(senderId, response.text()));
	}

	private MessagingChannel requireChannel(String channel) {
		Assert.hasText(channel, "Channel is required");
		MessagingChannel adapter = channels.get(channel);
		if (adapter == null) {
			throw new IllegalArgumentException("Unsupported messaging channel");
		}
		return adapter;
	}
}
