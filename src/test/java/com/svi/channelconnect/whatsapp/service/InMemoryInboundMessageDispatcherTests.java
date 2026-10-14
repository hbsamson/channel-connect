package com.svi.channelconnect.whatsapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.svi.channelconnect.whatsapp.model.DispatchResult;
import com.svi.channelconnect.whatsapp.model.InboundMessage;
import com.svi.channelconnect.whatsapp.service.InMemoryInboundMessageDispatcher;
import com.svi.channelconnect.whatsapp.service.MessageProcessingService;
import com.svi.channelconnect.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryInboundMessageDispatcherTests {
	private InMemoryInboundMessageDispatcher dispatcher;

	@AfterEach
	void tearDown() {
		if (dispatcher != null) {
			dispatcher.shutdown();
		}
	}

	@Test
	void suppressesDuplicateMessageIds() throws Exception {
		MessageProcessingService processor = mock(MessageProcessingService.class);
		CountDownLatch processed = new CountDownLatch(1);
		doAnswer(invocation -> {
			processed.countDown();
			return null;
		}).when(processor).process(any());
		dispatcher = new InMemoryInboundMessageDispatcher(
				processor, TestProperties.dispatch(1, 2), new SimpleMeterRegistry());
		InboundMessage message = message("id-1", "conversation-1", "first");

		assertThat(dispatcher.dispatch(message)).isEqualTo(DispatchResult.ACCEPTED);
		assertThat(dispatcher.dispatch(message)).isEqualTo(DispatchResult.DUPLICATE);
		assertThat(processed.await(1, TimeUnit.SECONDS)).isTrue();
		verify(processor, times(1)).process(any());
	}

	@Test
	void preservesOrderWithinConversation() throws Exception {
		MessageProcessingService processor = mock(MessageProcessingService.class);
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch processed = new CountDownLatch(3);
		doAnswer(invocation -> {
			InboundMessage value = invocation.getArgument(0);
			order.add(value.text());
			processed.countDown();
			return null;
		}).when(processor).process(any());
		dispatcher = new InMemoryInboundMessageDispatcher(
				processor, TestProperties.dispatch(2, 4), new SimpleMeterRegistry());

		dispatcher.dispatch(message("id-1", "conversation-1", "first"));
		dispatcher.dispatch(message("id-2", "conversation-1", "second"));
		dispatcher.dispatch(message("id-3", "conversation-1", "third"));

		assertThat(processed.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(order).containsExactly("first", "second", "third");
	}

	@Test
	void reportsOverloadAndReleasesRejectedMessageReservation() throws Exception {
		MessageProcessingService processor = mock(MessageProcessingService.class);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			started.countDown();
			release.await(1, TimeUnit.SECONDS);
			return null;
		}).when(processor).process(any());
		dispatcher = new InMemoryInboundMessageDispatcher(
				processor, TestProperties.dispatch(1, 1), new SimpleMeterRegistry());

		assertThat(dispatcher.dispatch(message("id-1", "same", "first")))
				.isEqualTo(DispatchResult.ACCEPTED);
		assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(dispatcher.dispatch(message("id-2", "same", "second")))
				.isEqualTo(DispatchResult.ACCEPTED);
		assertThat(dispatcher.dispatch(message("id-3", "same", "third")))
				.isEqualTo(DispatchResult.OVERLOADED);
		release.countDown();
	}

	private InboundMessage message(String id, String conversation, String text) {
		return new InboundMessage(id, conversation, conversation, text, Instant.EPOCH);
	}
}
