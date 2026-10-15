package com.svi.channelconnect.messenger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import com.svi.channelconnect.dealership.model.IncomingMessage;
import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.ProviderException;

class MessengerWorkflowTests {

	private final ConversationService conversations = mock(ConversationService.class);
	private final DealershipConversationService dealership = mock(DealershipConversationService.class);
	private final MessengerTmbgDemoService demo = mock(MessengerTmbgDemoService.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<MessengerTmbgDemoService> demos = mock(ObjectProvider.class);
	private final MessengerWorkflow workflow = new MessengerWorkflow(conversations, dealership, demos);
	private final IncomingMessageDTO incoming = new IncomingMessageDTO("MESSENGER", "page-1", "mid-1",
			"person-1", "page-1", "Hello", "TEXT", Instant.EPOCH);

	@Test
	void routesToTheBookingDemoWithoutReadingSharedConversationContext() {
		when(demos.getIfAvailable()).thenReturn(demo);

		workflow.process(incoming);

		verify(demo).process(incoming);
		verifyNoInteractions(conversations, dealership);
	}

	@Test
	void retainsTheExistingWorkflowWhenTheDemoIsUnavailable() {
		workflow.process(incoming);

		verify(dealership).receive(new IncomingMessage("MESSENGER", "page-1", "person-1", "mid-1", "Hello", Instant.EPOCH));
		verifyNoInteractions(demo, conversations);
	}

	@Test
	void restoresCorrelationAndDoesNotFallBackAfterADemoSendFailure() {
		when(demos.getIfAvailable()).thenReturn(demo);
		doThrow(new ProviderException("messenger", "rate_limit", null)).when(demo).process(incoming);
		String previous = MDC.get("correlationId");
		MDC.put("correlationId", "original-correlation");
		try {
			workflow.process(incoming);

			assertThat(MDC.get("correlationId")).isEqualTo("original-correlation");
			verifyNoInteractions(dealership, conversations);
		} finally {
			if (previous == null) MDC.remove("correlationId");
			else MDC.put("correlationId", previous);
		}
	}
}
