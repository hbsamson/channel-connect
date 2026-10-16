package com.svi.channelconnect.whatsapp.service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

/** WhatsApp transport for the single-process booking demo. */
@Service
@Profile({"local", "test"})
public class WhatsAppTmbgDemoService {
	private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppTmbgDemoService.class);
	private static final Duration REPLY_WINDOW = Duration.ofHours(24);

	private final TmbgBookingAssistant assistant;
	private final WhatsAppApiClient sender;
	private final String accountId;
	private final Clock clock;
	private final Map<String, PendingReply> pending = new HashMap<>();

	public WhatsAppTmbgDemoService(TmbgBookingAssistant assistant, WhatsAppApiClient sender,
			WhatsAppProperties properties, Clock clock) {
		this.assistant = assistant;
		this.sender = sender;
		this.accountId = properties.phoneNumberId();
		this.clock = clock;
	}

	public synchronized void process(InboundMessage incoming) {
		if (!incoming.receivedAt().plus(REPLY_WINDOW).isAfter(clock.instant())) {
			LOGGER.warn("WhatsApp booking demo ignored message outside reply window");
			return;
		}
		LOGGER.info("WhatsApp processing stage=booking_demo");
		PendingReply prepared = pending.get(incoming.senderWaId());
		if (prepared != null) {
			sender.sendTextMessage(incoming.senderWaId(), prepared.content());
			pending.remove(incoming.senderWaId());
			if (prepared.receiptId().equals(incoming.messageId())) return;
		}
		String reply = assistant.reply("WHATSAPP", accountId, incoming.senderWaId(),
				incoming.messageId(), incoming.text());
		if (reply != null) {
			// Retain a failed reply, including a saved booking's confirmation, for the next inbound turn.
			pending.put(incoming.senderWaId(), new PendingReply(incoming.messageId(), reply));
			sender.sendTextMessage(incoming.senderWaId(), reply);
			pending.remove(incoming.senderWaId());
		}
	}

	private record PendingReply(String receiptId, String content) { }
}
