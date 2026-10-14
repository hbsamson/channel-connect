package com.svi.channelconnect.whatsapp.service;

import static org.mockito.Mockito.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.support.TestProperties;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

class MessageProcessingServiceTests {
	@Test
	void normalizesWhatsAppIntoSharedCustomerConversation() {
		var shared = mock(DealershipConversationService.class);
		var properties = TestProperties.whatsApp();
		new MessageProcessingService(shared, properties).process(new InboundMessage("wamid.1", "legacy-id", "15551234567", "Hello", Instant.EPOCH));
		verify(shared).receive(new IncomingMessage("WHATSAPP", properties.phoneNumberId(), "15551234567", "wamid.1", "Hello", Instant.EPOCH));
	}
}
