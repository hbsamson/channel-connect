package com.svi.channelconnect.whatsapp.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.common.service.MessagingChannel;
import com.svi.channelconnect.whatsapp.client.WhatsAppApiClient;

@Service
public class WhatsAppMessagingChannel implements MessagingChannel {
	private final WhatsAppApiClient client;

	public WhatsAppMessagingChannel(WhatsAppApiClient client) {
		this.client = client;
	}

	@Override
	public String getChannelName() {
		return "WHATSAPP";
	}

	@Override
	public SendMessageResponseDTO sendMessage(OutgoingMessageDTO message) {
		return new SendMessageResponseDTO("ACCEPTED_BY_PROVIDER",
				client.sendTextMessage(message.recipientId(), message.content()));
	}
}
