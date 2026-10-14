package com.svi.messaging.whatsapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.svi.channelconnect.whatsapp.messaging.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.client.WhatsAppApiClient;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.config.OutboundMessagingProperties;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request.OutboundTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.DuplicateOutboundSubmissionException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.InvalidOutboundRequestException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.OutboundAuthorizationException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.OutboundCapacityException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.exception.WhatsAppApiException;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.InMemoryOutboundIdempotencyStore;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.OutboundMessageService;
import com.svi.messaging.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundMessageServiceTests {
	private WhatsAppApiClient whatsAppApiClient;
	private OutboundMessageService service;
	private InMemoryOutboundIdempotencyStore store;

	@BeforeEach
	void setUp() {
		whatsAppApiClient = mock(WhatsAppApiClient.class);
		OutboundMessagingProperties properties = TestProperties.outbound();
		store = new InMemoryOutboundIdempotencyStore(properties);
		service = new OutboundMessageService(whatsAppApiClient, properties, store);
	}

	@Test
	void sendsAllowedTemplateAndReplaysCompletedSubmission() {
		OutboundTemplateMessageRequest request = helloWorld("639123456789");
		when(whatsAppApiClient.sendTemplateMessage(
				request.recipient(), request.templateName(), request.languageCode(), request.parameters()))
				.thenReturn("wamid.ACCEPTED");

		var accepted = service.sendTemplateMessage("request-123", request);
		var replay = service.sendTemplateMessage("request-123", request);

		assertThat(accepted.providerMessageId()).isEqualTo("wamid.ACCEPTED");
		assertThat(accepted.idempotentReplay()).isFalse();
		assertThat(replay.providerMessageId()).isEqualTo("wamid.ACCEPTED");
		assertThat(replay.idempotentReplay()).isTrue();
		verify(whatsAppApiClient).sendTemplateMessage(
				request.recipient(), request.templateName(), request.languageCode(), request.parameters());
	}

	@Test
	void rejectsIdempotencyKeyReuseWithDifferentRequest() {
		OutboundTemplateMessageRequest first = helloWorld("639123456789");
		when(whatsAppApiClient.sendTemplateMessage(
				first.recipient(), first.templateName(), first.languageCode(), first.parameters()))
				.thenReturn("wamid.ACCEPTED");
		service.sendTemplateMessage("same-key", first);

		assertThatThrownBy(() -> service.sendTemplateMessage(
				"same-key", helloWorld("15551234567")))
				.isInstanceOf(DuplicateOutboundSubmissionException.class);
	}

	@Test
	void rejectsInvalidKeyAndNonAllowlistedRecipientBeforeCallingMeta() {
		assertThatThrownBy(() -> service.sendTemplateMessage(null, helloWorld("639123456789")))
				.isInstanceOf(InvalidOutboundRequestException.class);
		assertThatThrownBy(() -> service.sendTemplateMessage("valid-key", helloWorld("447700900123")))
				.isInstanceOf(OutboundAuthorizationException.class);
		assertThatThrownBy(() -> service.sendTemplateMessage("valid-key", new OutboundTemplateMessageRequest(
				"639123456789", "unapproved_template", "en_US", List.of())))
				.isInstanceOf(OutboundAuthorizationException.class);
		verifyNoInteractions(whatsAppApiClient);
	}

	@Test
	void blocksRepeatAfterAmbiguousProviderFailure() {
		OutboundTemplateMessageRequest request = helloWorld("639123456789");
		when(whatsAppApiClient.sendTemplateMessage(
				request.recipient(), request.templateName(), request.languageCode(), request.parameters()))
				.thenThrow(new WhatsAppApiException(ExternalFailureCategory.TIMEOUT, "timeout"));

		assertThatThrownBy(() -> service.sendTemplateMessage("timeout-key", request))
				.isInstanceOf(WhatsAppApiException.class);
		assertThatThrownBy(() -> service.sendTemplateMessage("timeout-key", request))
				.isInstanceOf(DuplicateOutboundSubmissionException.class)
				.hasMessageContaining("unknown");
		verify(whatsAppApiClient).sendTemplateMessage(
				request.recipient(), request.templateName(), request.languageCode(), request.parameters());
	}

	@Test
	void releasesReservationAfterDefinitiveProviderRejection() {
		OutboundTemplateMessageRequest request = helloWorld("639123456789");
		when(whatsAppApiClient.sendTemplateMessage(
				request.recipient(), request.templateName(), request.languageCode(), request.parameters()))
				.thenThrow(new WhatsAppApiException(ExternalFailureCategory.AUTHENTICATION, "unauthorized"))
				.thenReturn("wamid.RETRIED");

		assertThatThrownBy(() -> service.sendTemplateMessage("auth-key", request))
				.isInstanceOf(WhatsAppApiException.class);
		assertThat(service.sendTemplateMessage("auth-key", request).providerMessageId())
				.isEqualTo("wamid.RETRIED");
	}

	@Test
	void rejectsSubmissionWhenIdempotencyStoreIsFull() {
		OutboundMessagingProperties properties = TestProperties.outbound(1);
		var limitedStore = new InMemoryOutboundIdempotencyStore(properties);
		limitedStore.reserve("occupied", "fingerprint");
		var limitedService = new OutboundMessageService(whatsAppApiClient, properties, limitedStore);

		assertThatThrownBy(() -> limitedService.sendTemplateMessage(
				"another", helloWorld("639123456789")))
				.isInstanceOf(OutboundCapacityException.class);
	}

	private OutboundTemplateMessageRequest helloWorld(String recipient) {
		return new OutboundTemplateMessageRequest(recipient, "hello_world", "en_US", List.of());
	}
}
