package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.ai.client.AiClient;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.telegram.client.TelegramApiClient;
import com.svi.channelconnect.telegram.model.TelegramInboundMessage;
import org.springframework.util.StringUtils;

public class TelegramMessageProcessingService {
	private final AiClient aiClient;
	private final TelegramApiClient telegramApiClient;

	public TelegramMessageProcessingService(AiClient aiClient, TelegramApiClient telegramApiClient) {
		this.aiClient = aiClient;
		this.telegramApiClient = telegramApiClient;
	}

	public void process(TelegramInboundMessage message) {
		var response = aiClient.generateResponse(new AiRequest(message.conversationId(), message.text()));
		if (response == null || !StringUtils.hasText(response.text()) || response.text().length() > 4096) {
			throw new AiClientException(ExternalFailureCategory.MALFORMED_RESPONSE,
					"AI provider returned an invalid Telegram text response", null);
		}
		telegramApiClient.sendTextMessage(message.chatId(), message.messageThreadId(), response.text());
	}
}
