package com.svi.channelconnect.whatsapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.svi.channelconnect.whatsapp.exception.MalformedWebhookException;
import com.svi.channelconnect.whatsapp.mapper.WhatsAppMessageMapper;
import com.svi.channelconnect.whatsapp.model.DispatchResult;
import com.svi.channelconnect.whatsapp.model.InboundMessage;
import com.svi.channelconnect.whatsapp.service.InboundMessageDispatcher;
import com.svi.channelconnect.whatsapp.service.WhatsAppWebhookService;
import com.svi.channelconnect.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class WhatsAppWebhookServiceTests {
	private InboundMessageDispatcher dispatcher;
	private WhatsAppWebhookService service;

	@BeforeEach
	void setUp() {
		dispatcher = mock(InboundMessageDispatcher.class);
		service = new WhatsAppWebhookService(
				new ObjectMapper(),
				new WhatsAppMessageMapper(TestProperties.whatsApp()),
				dispatcher,
				new SimpleMeterRegistry());
	}

	@Test
	void dispatchesValidInboundTextMessage() throws Exception {
		when(dispatcher.dispatch(any())).thenReturn(DispatchResult.ACCEPTED);

		var result = service.handle(fixture("text-message.json"));

		assertThat(result.accepted()).isEqualTo(1);
		ArgumentCaptor<InboundMessage> message = ArgumentCaptor.forClass(InboundMessage.class);
		verify(dispatcher).dispatch(message.capture());
		assertThat(message.getValue().messageId()).isEqualTo("wamid.TEST_TEXT_1");
		assertThat(message.getValue().conversationId()).isEqualTo("15551234567");
		assertThat(message.getValue().text()).isEqualTo("Hello from WhatsApp");
	}

	@Test
	void reportsDuplicateMessageWithoutAcceptingItAgain() throws Exception {
		when(dispatcher.dispatch(any())).thenReturn(DispatchResult.DUPLICATE);

		var result = service.handle(fixture("text-message.json"));

		assertThat(result.duplicates()).isEqualTo(1);
		assertThat(result.accepted()).isZero();
	}

	@Test
	void ignoresDeliveryStatusAndUnsupportedMessageType() throws Exception {
		var status = service.handle(fixture("status-event.json"));
		var unsupported = service.handle(fixture("unsupported-image.json"));

		assertThat(status.statuses()).isEqualTo(1);
		assertThat(unsupported.unsupported()).isEqualTo(1);
		verify(dispatcher, never()).dispatch(any());
	}

	@Test
	void keepsMultipleConversationsIndependent() throws Exception {
		when(dispatcher.dispatch(any())).thenReturn(DispatchResult.ACCEPTED);

		var result = service.handle(fixture("multiple-messages.json"));

		assertThat(result.accepted()).isEqualTo(2);
		ArgumentCaptor<InboundMessage> messages = ArgumentCaptor.forClass(InboundMessage.class);
		verify(dispatcher, times(2)).dispatch(messages.capture());
		assertThat(messages.getAllValues())
				.extracting(InboundMessage::conversationId)
				.containsExactly("15551230001", "15551230002");
	}

	@Test
	void ignoresIncompleteMessageFields() {
		byte[] body = ("""
				{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{
				"messaging_product":"whatsapp","metadata":{"phone_number_id":"123456789"},
				"messages":[{"from":"15551234567","type":"text","text":{"body":"Hello"}}]}}]}]}
				""").getBytes(StandardCharsets.UTF_8);

		var result = service.handle(body);

		assertThat(result.invalid()).isEqualTo(1);
		verify(dispatcher, never()).dispatch(any());
	}

	@Test
	void rejectsMalformedJsonAndUnexpectedEnvelope() {
		assertThatThrownBy(() -> service.handle("{".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(MalformedWebhookException.class);
		assertThatThrownBy(() -> service.handle("{\"object\":\"page\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(MalformedWebhookException.class);
	}

	private byte[] fixture(String name) throws IOException {
		try (var stream = getClass().getResourceAsStream("/fixtures/whatsapp/" + name)) {
			if (stream == null) {
				throw new IOException("Fixture not found: " + name);
			}
			return stream.readAllBytes();
		}
	}
}
