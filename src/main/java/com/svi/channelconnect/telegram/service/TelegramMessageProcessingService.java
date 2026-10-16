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
	private final TelegramTmbgDemoService bookingDemo;

	public TelegramMessageProcessingService(DealershipConversationService conversations,
			TelegramProperties properties, Clock clock) {
		this(conversations, properties, clock, null);
	}

	public TelegramMessageProcessingService(DealershipConversationService conversations,
			TelegramProperties properties, Clock clock, TelegramTmbgDemoService bookingDemo) {
		this.conversations = conversations;
		// The numeric prefix identifies the bot without retaining its secret token.
		this.botId = properties.botToken().substring(0, properties.botToken().indexOf(':'));
		this.clock = clock;
		this.bookingDemo = bookingDemo;
	}

	public void process(TelegramInboundMessage message) {
		// The shared workflow supplies dealership context, stores history and sends through TELEGRAM.
		// updateId is unique per bot; messageId alone can collide between separate chats.
		var incoming = new IncomingMessage("TELEGRAM", botId,
				message.recipientId(), Long.toString(message.updateId()), message.text(), clock.instant());
		if (bookingDemo != null) {
			bookingDemo.process(incoming);
			return;
		}
		var result = conversations.receive(incoming);
		if ("UNKNOWN".equals(result.deliveryStatus())) {
			throw new TelegramApiException(ExternalFailureCategory.TRANSIENT,
					"Telegram dealership reply submission outcome is unknown");
		}
	}
}
