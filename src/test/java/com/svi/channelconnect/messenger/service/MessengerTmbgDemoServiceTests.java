package com.svi.channelconnect.messenger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.messenger.client.MessengerSendClient;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.ProviderException;

class MessengerTmbgDemoServiceTests {

	private final TmbgBookingAssistant assistant = mock(TmbgBookingAssistant.class);
	private final MessengerSendClient sender = mock(MessengerSendClient.class);
	private final MessengerTmbgDemoService demo = new MessengerTmbgDemoService(assistant, sender);

	@Test
	void forwardsThePreparedGreetingToTheMessengerParticipant() {
		when(assistant.reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello"))
				.thenReturn("Welcome to the booking demo.");

		demo.process(incoming("page-1", "person-1", "mid-1"));

		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello");
		verify(sender).send(new OutgoingMessageDTO("person-1", "Welcome to the booking demo.", "page-1", "mid-1", null));
	}

	@Test
	void suppressesDuplicateDeliveriesWithinTheirPageAndSenderOnly() {
		when(assistant.reply(eq("MESSENGER"), anyString(), anyString(), eq("mid-1"), eq("Hello")))
				.thenReturn("Choose a slot.");
		var first = incoming("page-1", "person-1", "mid-1");

		demo.process(first);
		demo.process(first);
		demo.process(incoming("page-2", "person-1", "mid-1"));
		demo.process(incoming("page-1", "person-2", "mid-1"));

		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello");
		verify(assistant).reply("MESSENGER", "page-2", "person-1", "mid-1", "Hello");
		verify(assistant).reply("MESSENGER", "page-1", "person-2", "mid-1", "Hello");
		verifyNoMoreInteractions(assistant);
		verify(sender, times(3)).send(any());
	}

	@Test
	void sendsNothingForACompletedConversationAndSuppressesItsDuplicate() {
		var incoming = incoming("page-1", "person-1", "mid-1");

		demo.process(incoming);
		demo.process(incoming);

		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello");
		verifyNoMoreInteractions(assistant);
		verifyNoInteractions(sender);
	}

	@Test
	void retriesThePreparedReplyAfterAFailedSubmission() {
		var incoming = incoming("page-1", "person-1", "mid-1");
		when(assistant.reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello"))
				.thenReturn("Booking confirmed. Reference TMB-00001.");
		when(sender.send(any())).thenThrow(new ProviderException("messenger", "rate_limit", null))
				.thenReturn(new SendMessageResponseDTO("SENT", "reply-1"));

		assertThatThrownBy(() -> demo.process(incoming)).isInstanceOf(ProviderException.class);
		demo.process(incoming);
		demo.process(incoming);

		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello");
		verifyNoMoreInteractions(assistant);
		verify(sender, times(2)).send(new OutgoingMessageDTO("person-1", "Booking confirmed. Reference TMB-00001.",
				"page-1", "mid-1", null));
	}

	@Test
	void recoversAFailedBookingConfirmationOnTheNextCustomerMessage() {
		var original = incoming("page-1", "person-1", "mid-1");
		var followUp = incoming("page-1", "person-1", "mid-2");
		when(assistant.reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello"))
				.thenReturn("Booking confirmed. Reference TMB-00001.");
		when(assistant.reply("MESSENGER", "page-1", "person-1", "mid-2", "Hello")).thenReturn(null);
		when(sender.send(any())).thenThrow(new ProviderException("messenger", "rate_limit", null))
				.thenReturn(new SendMessageResponseDTO("SENT", "reply-1"));

		assertThatThrownBy(() -> demo.process(original)).isInstanceOf(ProviderException.class);
		demo.process(followUp);
		demo.process(original);
		demo.process(followUp);

		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-1", "Hello");
		verify(assistant).reply("MESSENGER", "page-1", "person-1", "mid-2", "Hello");
		verify(sender, times(2)).send(new OutgoingMessageDTO("person-1", "Booking confirmed. Reference TMB-00001.",
				"page-1", "mid-1", null));
		verifyNoMoreInteractions(assistant, sender);
	}

	private IncomingMessageDTO incoming(String page, String participant, String receipt) {
		return new IncomingMessageDTO("MESSENGER", page, receipt, participant, page, "Hello", "TEXT", Instant.EPOCH);
	}
}
