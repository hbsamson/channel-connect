package com.svi.channelconnect.sms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.svi.channelconnect.sms.dto.response.SmsReceiveResponse;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.support.SmsTestProperties;

class SmsReceiveServiceTests {
	private final SmsMessageRepository messages = mock(SmsMessageRepository.class);
	private final SmsReceiveService sms = new SmsReceiveService(SmsTestProperties.properties(Map.of()), messages);

	@Test void returnsEmptyPollWithoutAdvancingCursor() {
		when(messages.received("sim-1", null, 17, 10)).thenReturn(List.of());
		var result = sms.receive("sim-1", null, 17, 10, 0);
		assertThat(result.getResult()).isEqualTo(new SmsReceiveResponse(List.of(), 17));
	}

	@Test void normalizesParticipantAndCompletesWaitingPollWhenReceiptArrives() {
		when(messages.received("sim-1", "+639000000001", 17, 10)).thenReturn(List.of());
		var result = sms.receive("sim-1", "09000000001", 17, 10, 25);
		assertThat(result.hasResult()).isFalse();
		var receipt = new SmsReceiveResponse.Message(20, "inbox-1", 8, "+639000000001", "Question", 1, "RECEIVED");
		when(messages.received("sim-1", "+639000000001", 17, 10)).thenReturn(List.of(receipt));
		sms.refresh();
		assertThat(result.getResult()).isEqualTo(new SmsReceiveResponse(List.of(receipt), 20));
		verify(messages, never()).claimInbound(anyString());
	}

	@Test void boundsWaitersAndReleasesThemOnShutdown() {
		when(messages.received(anyString(), isNull(), anyLong(), anyInt())).thenReturn(List.of());
		for (int i = 0; i < 100; i++) sms.receive("sim-1", null, 0, 10, 30);
		assertReason(() -> sms.receive("sim-1", null, 0, 10, 30), SmsException.Reason.CAPACITY);
		sms.close();
		assertThat(sms.receive("sim-1", null, 0, 10, 0).hasResult()).isTrue();
	}

	@Test void rejectsUnknownAccountMalformedSenderAndInvalidPollingBounds() {
		assertReason(() -> sms.receive("unknown", null, 0, 10, 0), SmsException.Reason.FORBIDDEN);
		assertReason(() -> sms.receive("sim-1", "invalid", 0, 10, 0), SmsException.Reason.INVALID);
		assertReason(() -> sms.receive("sim-1", null, -1, 10, 0), SmsException.Reason.INVALID);
		assertReason(() -> sms.receive("sim-1", null, 0, 101, 0), SmsException.Reason.INVALID);
		assertReason(() -> sms.receive("sim-1", null, 0, 10, 31), SmsException.Reason.INVALID);
		verifyNoInteractions(messages);
	}

	private void assertReason(Runnable action, SmsException.Reason reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(SmsException.class, exception -> assertThat(exception.reason()).isEqualTo(reason));
	}
}
