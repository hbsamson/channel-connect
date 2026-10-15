package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.common.service.MessagingChannel;
import com.svi.channelconnect.telegram.client.TelegramApiClient;
import com.svi.channelconnect.telegram.exception.TelegramApiException;

/** Registers Telegram with the shared messaging handler. Recipients use chatId[:topicId]. */
public class TelegramMessagingChannel implements MessagingChannel {
	private final TelegramApiClient apiClient;

	public TelegramMessagingChannel(TelegramApiClient apiClient) {
		this.apiClient = apiClient;
	}

	@Override
	public String getChannelName() {
		return "TELEGRAM";
	}

	@Override
	public SendMessageResponseDTO sendMessage(OutgoingMessageDTO message) {
		if (message == null || message.recipientId() == null
				|| !message.recipientId().matches("-?[0-9]{1,19}(:[0-9]{1,19})?")) {
			throw invalidRecipient();
		}
		String[] recipient = message.recipientId().split(":");
		long chatId;
		Long topicId;
		try {
			chatId = Long.parseLong(recipient[0]);
			topicId = recipient.length == 2 ? Long.valueOf(recipient[1]) : null;
		}
		catch (NumberFormatException exception) {
			throw invalidRecipient();
		}
		long messageId = apiClient.sendTextMessage(chatId, topicId, message.content());
		return new SendMessageResponseDTO("ACCEPTED_BY_PROVIDER", Long.toString(messageId));
	}

	private TelegramApiException invalidRecipient() {
		return new TelegramApiException(ExternalFailureCategory.INVALID_REQUEST,
				"Telegram recipient must be a numeric chat ID with an optional topic ID");
	}
}
