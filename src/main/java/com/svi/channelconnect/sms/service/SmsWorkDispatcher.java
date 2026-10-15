package com.svi.channelconnect.sms.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.provider.SmsProvider;

@Service
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
public class SmsWorkDispatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger(SmsWorkDispatcher.class);
	private final SmsMessageRepository store;
	private final SmsConversationProcessor conversations;
	private final SmsProvider provider;
	private final SmsProperties properties;
	private final MeterRegistry metrics;
	private final ThreadPoolExecutor inbound;
	private final ThreadPoolExecutor outbound;
	private volatile boolean ready;
	public SmsWorkDispatcher(SmsMessageRepository store, SmsConversationProcessor conversations, SmsProvider provider,
			SmsProperties properties, MeterRegistry metrics) {
		this.store = store; this.conversations = conversations; this.provider = provider; this.properties = properties; this.metrics = metrics;
		inbound = executor(1, "sms-inbound"); outbound = executor(properties.maxSessions(), "sms-outbound");
		metrics.gauge("sms.inbox.backlog", store, SmsMessageRepository::inboundBacklog);
		metrics.gauge("sms.outbox.backlog", store, SmsMessageRepository::outboundBacklog);
	}
	private ThreadPoolExecutor executor(int count, String name) {
		return new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(count), task -> {
			Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}
	@EventListener(ApplicationReadyEvent.class)
	public void start() { store.recover(); ready = true; }
	@Scheduled(fixedDelayString = "${sms.dispatch-delay-ms:200}")
	public synchronized void tick() {
		if (!ready) return;
		if (inbound.getActiveCount() == 0 && inbound.getQueue().isEmpty()) {
			for (var message : store.pendingInbound()) {
				if (!store.claimInbound(message.id())) continue;
				try { inbound.execute(() -> processInbound(message.id())); }
				catch (java.util.concurrent.RejectedExecutionException exception) { store.retryInbound(message.id()); }
			}
		}
		for (SmsOutbound message : store.pendingOutbound()) {
			if (outbound.getActiveCount() + outbound.getQueue().size() >= properties.maxSessions()) break;
			if (!store.authorized(message)) { store.finish(message.id(), "FAILED", "AUTHORIZATION_REVOKED"); continue; }
			if (!provider.available(message.accountId()) || !store.rateAvailable(message.accountId()) || !store.claimOutbound(message.id())) continue;
			try { outbound.execute(() -> processOutbound(message)); }
			catch (java.util.concurrent.RejectedExecutionException exception) { store.finish(message.id(), "QUEUED", "WORKER_BUSY"); }
		}
	}
	private void processInbound(String id) {
		String previous = org.slf4j.MDC.get("correlationId"); org.slf4j.MDC.put("correlationId", id);
		long started = System.nanoTime();
		LOGGER.info("SMS processing started; messageId={}", id);
		try {
			conversations.process(id); metrics.counter("sms.processing", "result", "completed").increment();
			LOGGER.info("SMS processing completed; messageId={}, elapsedMs={}", id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		}
		catch (RuntimeException exception) {
			store.retryInbound(id); metrics.counter("sms.processing", "result", "retry").increment();
			if (store.inbound(id).status().equals("DEAD_LETTER")) metrics.counter("sms.processing", "result", "dead_letter").increment();
			LOGGER.warn("SMS processing deferred; messageId={}, category={}", id, exception.getClass().getSimpleName());
		} finally { restore(previous); }
	}
	private void processOutbound(SmsOutbound message) {
		String previous = org.slf4j.MDC.get("correlationId"); org.slf4j.MDC.put("correlationId", message.id());
		long started = System.nanoTime();
		try {
			var result = provider.send(message, () -> store.transmitting(message.id()));
			store.finish(message.id(), result.status(), result.errorCode());
			metrics.counter("sms.outbound", "result", result.status()).increment();
			LOGGER.info("SMS send completed; messageId={}, status={}, elapsedMs={}", message.id(), result.status(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
		} catch (RuntimeException exception) {
			String persisted = store.outbound(message.id()).orElseThrow().status();
			String result = persisted.equals("TRANSMITTING") ? "UNKNOWN"
					: exception instanceof SmsException sms && sms.reason() == SmsException.Reason.CAPACITY ? "QUEUED" : "FAILED";
			store.finish(message.id(), result, exception instanceof SmsException sms ? sms.reason().name() : "WORKER_FAILURE");
			metrics.counter("sms.outbound", "result", result).increment();
			LOGGER.warn("SMS send worker failed; messageId={}, status={}", message.id(), result);
		} finally { restore(previous); }
	}
	private void restore(String previous) { if (previous == null) org.slf4j.MDC.remove("correlationId"); else org.slf4j.MDC.put("correlationId", previous); }
	@Scheduled(fixedDelayString = "${sms.maintenance-delay-ms:60000}")
	public void maintain() { if (ready) store.maintain(); }
	@PreDestroy
	public synchronized void close() {
		ready = false; inbound.shutdown(); outbound.shutdown();
		long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
		for (ThreadPoolExecutor executor : new ThreadPoolExecutor[] {inbound, outbound}) {
			try { if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) executor.shutdownNow(); }
			catch (InterruptedException exception) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
		}
	}
}
