package com.svi.channelconnect.telegram.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.telegram.config.TelegramProperties;
import com.svi.channelconnect.telegram.exception.TelegramApiException;
import com.svi.channelconnect.telegram.model.TelegramDispatchResult;
import com.svi.channelconnect.telegram.model.TelegramInboundMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, process-local development dispatcher. Accepted work and deduplication
 * state are lost on restart. Failed work is retained as seen to avoid ambiguous resends.
 */
public class InMemoryTelegramInboundMessageDispatcher implements TelegramInboundMessageDispatcher {
	private static final Logger log = LoggerFactory.getLogger(InMemoryTelegramInboundMessageDispatcher.class);

	private final TelegramMessageProcessingService processingService;
	private final TelegramProperties properties;
	private final ThreadPoolExecutor[] lanes;
	private final Map<Long, Instant> seenUpdates = new HashMap<>();
	private final Clock clock;
	private final Counter accepted;
	private final Counter duplicates;
	private final Counter overloaded;
	private final Counter failures;

	public InMemoryTelegramInboundMessageDispatcher(TelegramMessageProcessingService processingService,
			TelegramProperties properties, MeterRegistry registry) {
		this(processingService, properties, registry, Clock.systemUTC());
	}

	InMemoryTelegramInboundMessageDispatcher(TelegramMessageProcessingService processingService,
			TelegramProperties properties, MeterRegistry registry, Clock clock) {
		this.processingService = processingService;
		this.properties = properties;
		this.clock = clock;
		this.accepted = registry.counter("telegram.webhook.messages", "result", "accepted");
		this.duplicates = registry.counter("telegram.webhook.messages", "result", "duplicate");
		this.overloaded = registry.counter("telegram.webhook.messages", "result", "overloaded");
		this.failures = registry.counter("telegram.message.processing", "result", "failed");
		this.lanes = new ThreadPoolExecutor[properties.workerCount()];
		for (int index = 0; index < lanes.length; index++) {
			String threadName = "telegram-dispatch-" + index;
			lanes[index] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(properties.queueCapacityPerWorker()),
					task -> new Thread(task, threadName), new ThreadPoolExecutor.AbortPolicy());
		}
	}

	@Override
	public synchronized TelegramDispatchResult dispatch(TelegramInboundMessage message) {
		Instant now = clock.instant();
		seenUpdates.entrySet().removeIf(entry -> entry.getValue() != null
				&& !entry.getValue().isAfter(now));
		if (seenUpdates.containsKey(message.updateId())) {
			duplicates.increment();
			return TelegramDispatchResult.DUPLICATE;
		}
		if (seenUpdates.size() >= properties.deduplicationCapacity()) {
			overloaded.increment();
			return TelegramDispatchResult.OVERLOADED;
		}
		// A null expiry reserves queued/in-flight updates until processing finishes.
		seenUpdates.put(message.updateId(), null);
		var lane = lanes[Math.floorMod(message.conversationId().hashCode(), lanes.length)];
		try {
			lane.execute(() -> process(message));
			accepted.increment();
			return TelegramDispatchResult.ACCEPTED;
		}
		catch (RejectedExecutionException exception) {
			seenUpdates.remove(message.updateId());
			overloaded.increment();
			return TelegramDispatchResult.OVERLOADED;
		}
	}

	private void process(TelegramInboundMessage message) {
		try {
			processingService.process(message);
		}
		catch (RuntimeException exception) {
			failures.increment();
			String category = switch (exception) {
				case AiClientException aiFailure -> aiFailure.category().name();
				case TelegramApiException telegramFailure -> telegramFailure.category().name();
				default -> exception.getClass().getSimpleName();
			};
			log.error("Asynchronous Telegram processing failed; updateId={}, category={}",
					message.updateId(), category);
		}
		finally {
			complete(message.updateId());
		}
	}

	private synchronized void complete(long updateId) {
		seenUpdates.put(updateId, clock.instant().plus(properties.deduplicationTtl()));
	}

	@PreDestroy
	void shutdown() {
		for (var lane : lanes) {
			lane.shutdown();
		}
		long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
		for (var lane : lanes) {
			long remaining = deadline - System.nanoTime();
			try {
				if (remaining <= 0 || !lane.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
					lane.shutdownNow();
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				lane.shutdownNow();
			}
		}
	}
}
