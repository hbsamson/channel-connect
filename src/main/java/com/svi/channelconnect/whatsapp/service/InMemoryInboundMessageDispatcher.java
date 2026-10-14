package com.svi.channelconnect.whatsapp.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.whatsapp.config.DispatchProperties;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;
import com.svi.channelconnect.whatsapp.model.DispatchResult;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

@Service
@Profile({"local", "test"})
public class InMemoryInboundMessageDispatcher implements InboundMessageDispatcher {
	private static final Logger log = LoggerFactory.getLogger(InMemoryInboundMessageDispatcher.class);

	private final MessageProcessingService processingService;
	private final DispatchProperties properties;
	private final ThreadPoolExecutor[] lanes;
	private final Map<String, Instant> seenMessageIds = new LinkedHashMap<>();
	private final Clock clock;
	private final Counter accepted;
	private final Counter duplicates;
	private final Counter overloaded;
	private final Counter failures;

	@Autowired
	public InMemoryInboundMessageDispatcher(MessageProcessingService processingService,
			DispatchProperties properties, MeterRegistry registry) {
		this(processingService, properties, registry, Clock.systemUTC());
	}

	InMemoryInboundMessageDispatcher(MessageProcessingService processingService,
			DispatchProperties properties, MeterRegistry registry, Clock clock) {
		this.processingService = processingService;
		this.properties = properties;
		this.clock = clock;
		this.accepted = registry.counter("whatsapp.webhook.messages", "result", "accepted");
		this.duplicates = registry.counter("whatsapp.webhook.messages", "result", "duplicate");
		this.overloaded = registry.counter("whatsapp.webhook.messages", "result", "overloaded");
		this.failures = registry.counter("whatsapp.message.processing", "result", "failed");
		this.lanes = createLanes(properties, registry);
	}

	@Override
	public DispatchResult dispatch(InboundMessage message) {
		if (!reserve(message.messageId())) {
			duplicates.increment();
			return DispatchResult.DUPLICATE;
		}

		ThreadPoolExecutor lane = lanes[Math.floorMod(message.conversationId().hashCode(), lanes.length)];
		try {
			lane.execute(() -> process(message));
			accepted.increment();
			return DispatchResult.ACCEPTED;
		}
		catch (RejectedExecutionException exception) {
			release(message.messageId());
			overloaded.increment();
			return DispatchResult.OVERLOADED;
		}
	}

	private void process(InboundMessage message) {
		try {
			processingService.process(message);
		}
		catch (RuntimeException exception) {
			failures.increment();
			String category = switch (exception) {
				case AiClientException aiFailure -> aiFailure.category().name();
				case WhatsAppApiException whatsAppFailure -> whatsAppFailure.category().name();
				default -> exception.getClass().getSimpleName();
			};
			log.error("Asynchronous WhatsApp message processing failed; messageIdHash={}, category={}",
					Integer.toHexString(message.messageId().hashCode()), category);
		}
	}

	private synchronized boolean reserve(String messageId) {
		Instant now = clock.instant();
		removeExpired(now);
		if (seenMessageIds.containsKey(messageId)) {
			return false;
		}
		while (seenMessageIds.size() >= properties.deduplicationCapacity()) {
			Iterator<String> iterator = seenMessageIds.keySet().iterator();
			iterator.next();
			iterator.remove();
		}
		seenMessageIds.put(messageId, now.plus(properties.deduplicationTtl()));
		return true;
	}

	private synchronized void release(String messageId) {
		seenMessageIds.remove(messageId);
	}

	private void removeExpired(Instant now) {
		Iterator<Map.Entry<String, Instant>> iterator = seenMessageIds.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue().isAfter(now)) {
				break;
			}
			iterator.remove();
		}
	}

	private ThreadPoolExecutor[] createLanes(DispatchProperties settings, MeterRegistry registry) {
		ThreadPoolExecutor[] executors = new ThreadPoolExecutor[settings.workerCount()];
		int laneCapacity = Math.max(1,
				(int) Math.ceil((double) settings.queueCapacity() / settings.workerCount()));
		AtomicInteger threadNumber = new AtomicInteger();
		ThreadFactory factory = task -> new Thread(task,
				"whatsapp-dispatch-" + threadNumber.incrementAndGet());

		for (int index = 0; index < executors.length; index++) {
			ThreadPoolExecutor executor = new ThreadPoolExecutor(
					1, 1, 0, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(laneCapacity), factory,
					new ThreadPoolExecutor.AbortPolicy());
			ExecutorServiceMetrics.monitor(registry, executor, "whatsapp.dispatch",
					Tag.of("lane", Integer.toString(index)));
			executors[index] = executor;
		}
		return executors;
	}

	@PreDestroy
	void shutdown() {
		for (ThreadPoolExecutor lane : lanes) {
			lane.shutdown();
		}
		long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
		for (ThreadPoolExecutor lane : lanes) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				lane.shutdownNow();
				continue;
			}
			try {
				if (!lane.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
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
