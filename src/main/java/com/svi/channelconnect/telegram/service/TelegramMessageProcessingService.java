package com.svi.channelconnect.telegram.service;

import java.time.Clock;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.telegram.config.TelegramProperties;
import com.svi.channelconnect.telegram.exception.TelegramApiException;
import com.svi.channelconnect.telegram.model.TelegramInboundMessage;

public class TelegramMessageProcessingService {
	private final DealershipConversationService conversations;
	private final String botId;
	private final Clock clock;

	public TelegramMessageProcessingService(DealershipConversationService conversations,
			TelegramProperties properties, Clock clock) {
		this.conversations = conversations;
		// The numeric prefix identifies the bot without retaining its secret token.
		this.botId = properties.botToken().substring(0, properties.botToken().indexOf(':'));
		this.clock = clock;
	}

	public void process(TelegramInboundMessage message) {
		// The shared workflow supplies dealership context, stores history and sends through TELEGRAM.
		// updateId is unique per bot; messageId alone can collide between separate chats.
		var result = conversations.receive(new IncomingMessage("TELEGRAM", botId,
				message.recipientId(), Long.toString(message.updateId()), message.text(), clock.instant()));
		if ("UNKNOWN".equals(result.deliveryStatus())) {
			throw new TelegramApiException(ExternalFailureCategory.TRANSIENT,
					"Telegram dealership reply submission outcome is unknown");
		}
	}
}
