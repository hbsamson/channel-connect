package com.svi.channelconnect.whatsapp.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

@Service
public class MessageProcessingService {
	private final DealershipConversationService conversations;
	private final WhatsAppProperties properties;
	private final Optional<WhatsAppTmbgDemoService> demo;

	public MessageProcessingService(DealershipConversationService conversations, WhatsAppProperties properties) {
		this(conversations, properties, Optional.empty());
	}

	@Autowired
	public MessageProcessingService(DealershipConversationService conversations, WhatsAppProperties properties,
			Optional<WhatsAppTmbgDemoService> demo) {
		this.conversations = conversations;
		this.properties = properties;
		this.demo = demo;
	}

	public void process(InboundMessage message) {
		if (demo.isPresent()) {
			demo.get().process(message);
			return;
		}
		conversations.receive(new com.svi.channelconnect.dealership.model.IncomingMessage("WHATSAPP",
				properties.phoneNumberId(), message.senderWaId(), message.messageId(), message.text(), message.receivedAt()));
	}
}
