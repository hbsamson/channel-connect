package com.svi.channelconnect.whatsapp.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

@Service
public class MessageProcessingService {
	private final DealershipConversationService conversations;
	private final WhatsAppProperties properties;

	public MessageProcessingService(DealershipConversationService conversations, WhatsAppProperties properties) {
		this.conversations = conversations;
		this.properties = properties;
	}

	public void process(InboundMessage message) {
		conversations.receive(new com.svi.channelconnect.dealership.model.IncomingMessage("WHATSAPP",
				properties.phoneNumberId(), message.senderWaId(), message.messageId(), message.text(), message.receivedAt()));
	}
}
