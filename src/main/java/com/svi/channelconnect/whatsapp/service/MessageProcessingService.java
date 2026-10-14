package com.svi.channelconnect.whatsapp.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.MessagingAppHandler;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

@Service
public class MessageProcessingService {
	private final MessagingAppHandler handler;

	public MessageProcessingService(MessagingAppHandler handler) {
		this.handler = handler;
	}

	public void process(InboundMessage message) {
		handler.reply("WHATSAPP", message.conversationId(), message.senderWaId(), message.text());
	}
}
