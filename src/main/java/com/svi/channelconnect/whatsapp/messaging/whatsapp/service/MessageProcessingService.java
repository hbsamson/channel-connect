package com.svi.channelconnect.whatsapp.messaging.whatsapp.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.whatsapp.messaging.common.service.MessagingApplicationHandler;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.InboundMessage;

@Service
public class MessageProcessingService {
	private final MessagingApplicationHandler handler;

	public MessageProcessingService(MessagingApplicationHandler handler) {
		this.handler = handler;
	}

	public void process(InboundMessage message) {
		handler.reply("WHATSAPP", message.conversationId(), message.senderWaId(), message.text());
	}
}
