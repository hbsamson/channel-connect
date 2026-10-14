package com.svi.messaging.whatsapp.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.svi.channelconnect.whatsapp.messaging.ai.client.AiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiResponse;
import com.svi.channelconnect.whatsapp.messaging.ai.exception.AiClientException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.InboundMessage;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.MessageProcessingService;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.WhatsAppMessagingChannel;

class MessageProcessingServiceTests {
	private AiClient aiClient;
	private WhatsAppApiClient whatsAppApiClient;
	private MessageProcessingService service;
	private InboundMessage message;

	@BeforeEach
	void setUp() {
		aiClient = mock(AiClient.class);
		whatsAppApiClient = mock(WhatsAppApiClient.class);
		service = new MessageProcessingService(new com.svi.channelconnect.whatsapp.messaging.common.service.MessagingApplicationHandler(
				java.util.List.of(new WhatsAppMessagingChannel(whatsAppApiClient)), aiClient));
		message = new InboundMessage(
				"wamid.1", "15551234567", "15551234567", "Hello", Instant.EPOCH);
	}

	@Test
	void sendsAiResponseToOriginatingWhatsAppUser() {
		when(aiClient.generateResponse(new AiRequest("15551234567", "Hello")))
				.thenReturn(new AiResponse("Hi there"));

		service.process(message);

		verify(whatsAppApiClient).sendTextMessage("15551234567", "Hi there");
	}

	@Test
	void doesNotSendWhenAiResponseIsEmpty() {
		when(aiClient.generateResponse(new AiRequest("15551234567", "Hello")))
				.thenReturn(new AiResponse("  "));

		assertThatThrownBy(() -> service.process(message)).isInstanceOf(AiClientException.class);
		verify(whatsAppApiClient, never()).sendTextMessage("15551234567", "  ");
	}

	@Test
	void propagatesAiProviderFailureWithoutSending() {
		when(aiClient.generateResponse(new AiRequest("15551234567", "Hello")))
				.thenThrow(new IllegalStateException("provider unavailable"));

		assertThatThrownBy(() -> service.process(message)).isInstanceOf(IllegalStateException.class);
		verify(whatsAppApiClient, never()).sendTextMessage("15551234567", "Hello");
	}
}
