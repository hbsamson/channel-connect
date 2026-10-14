package com.svi.messaging.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.svi.channelconnect.whatsapp.messaging.ai.client.AiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiResponse;
import com.svi.channelconnect.whatsapp.messaging.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.whatsapp.messaging.common.service.MessagingApplicationHandler;
import com.svi.channelconnect.whatsapp.messaging.common.service.MessagingChannel;

class MessagingApplicationHandlerTests {
	@Test
	void routesToRequestedPlatformWithoutCallingOtherAdapters() {
		MessagingChannel messenger = mock(MessagingChannel.class);
		MessagingChannel whatsapp = mock(MessagingChannel.class);
		when(messenger.getChannelName()).thenReturn("MESSENGER");
		when(whatsapp.getChannelName()).thenReturn("WHATSAPP");
		AiClient ai = mock(AiClient.class);
		var handler = new MessagingApplicationHandler(List.of(messenger, whatsapp), ai);
		var message = new OutgoingMessageDTO("test-recipient", "test-reply");
		var accepted = new SendMessageResponseDTO("ACCEPTED_BY_PROVIDER", "test-id");
		when(whatsapp.sendMessage(message)).thenReturn(accepted);
		assertThat(handler.sendMessage("WHATSAPP", message)).isEqualTo(accepted);
		verify(messenger, never()).sendMessage(any());
		verifyNoInteractions(ai);
	}

	@Test
	void rejectsUnknownChannelBeforeGeneratingReply() {
		AiClient ai = mock(AiClient.class);
		var handler = new MessagingApplicationHandler(List.of(), ai);
		assertThatThrownBy(() -> handler.reply("UNKNOWN", "conversation", "sender", "hello"))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(ai);
	}

	@Test
	void rejectsDuplicateRegistrations() {
		MessagingChannel adapter = mock(MessagingChannel.class);
		when(adapter.getChannelName()).thenReturn("DUPLICATE");
		assertThatThrownBy(() -> new MessagingApplicationHandler(List.of(adapter, adapter), mock(AiClient.class)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void repliesThroughNewPlatformWithoutChangingHandler() {
		MessagingChannel adapter = mock(MessagingChannel.class);
		when(adapter.getChannelName()).thenReturn("NEW_PLATFORM");
		AiClient ai = mock(AiClient.class);
		when(ai.generateResponse(new AiRequest("conversation", "hello"))).thenReturn(new AiResponse("reply"));
		new MessagingApplicationHandler(List.of(adapter), ai).reply("NEW_PLATFORM", "conversation", "sender", "hello");
		verify(adapter).sendMessage(new OutgoingMessageDTO("sender", "reply"));
	}
}
