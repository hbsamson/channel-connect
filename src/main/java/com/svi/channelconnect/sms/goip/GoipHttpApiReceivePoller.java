package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.config.GoipHttpApiProperties;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsTmbgDemoService;
import com.svi.channelconnect.sms.service.SmsValidation;

/** Retains the hosted inbox; only eligible fresh customer replies enter conversation processing. */
@Component
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' == 'HTTP_API' || ('${sms.mode:MOCK}' == 'HTTP' && ${sms.http-api.receive-enabled:false})")
public class GoipHttpApiReceivePoller {
	private static final Logger LOGGER = LoggerFactory.getLogger(GoipHttpApiReceivePoller.class);
	private static final int MAX_SNAPSHOT_MESSAGES = 1000;
	private final GoipHttpApiClient client;
	private final GoipHttpApiProperties api;
	private final SmsProperties sms;
	private final SmsMessageRepository messages;
	private final DealershipRepository customers;
	private final ObjectMapper json;
	private final MeterRegistry metrics;
	private final Clock clock;
	private final ObjectProvider<SmsTmbgDemoService> demos;
	private final ThreadPoolExecutor worker;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile boolean ready;
	private volatile long receivedFrom;

	public GoipHttpApiReceivePoller(GoipHttpApiClient client, GoipHttpApiProperties api, SmsProperties sms,
			SmsMessageRepository messages, DealershipRepository customers, ObjectMapper json, MeterRegistry metrics, Clock clock,
			ObjectProvider<SmsTmbgDemoService> demos) {
		if (api.receiveUrl() == null) throw new IllegalArgumentException("SMS HTTP API polling requires a receive URL");
		this.client = client;
		this.api = api;
		this.sms = sms;
		this.messages = messages;
		this.customers = customers;
		this.json = json;
		this.metrics = metrics;
		this.clock = clock;
		this.demos = demos;
		worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
			Thread thread = new Thread(task, "sms-http-api-receive");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		long initial = api.receiveStartAt() == null ? clock.instant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
				: api.receiveStartAt().toEpochMilli();
		receivedFrom = messages.receiveBaseline(SmsValidation.fingerprint(api.receiveUrl().toString()), initial);
		ready = true;
	}

	@Scheduled(fixedDelayString = "${sms.poll-interval:PT3S}")
	public void tick() {
		if (!ready || !running.compareAndSet(false, true)) return;
		try {
			worker.execute(() -> {
				try { poll(); }
				finally { running.set(false); }
			});
		} catch (java.util.concurrent.RejectedExecutionException exception) {
			running.set(false);
		}
	}

	void poll() {
		if (!ready) return;
		long started = System.nanoTime();
		try {
			var response = client.receive();
			long fetchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			if (fetchMillis > sms.pollInterval().toMillis()) {
				LOGGER.warn("SMS receive API slow; elapsedMs={}, pollIntervalMs={}", fetchMillis, sms.pollInterval().toMillis());
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				failure("http_status", started);
				return;
			}
			List<RemoteMessage> received = snapshot(response.body());
			received.sort(Comparator.comparing(RemoteMessage::receivedAt).thenComparingLong(RemoteMessage::receiveId));
			SmsTmbgDemoService demo = demos.getIfAvailable();
			Map<DemoParticipant, RemoteMessage> latest = new HashMap<>();
			for (RemoteMessage message : received) {
				if (demo != null && message.processReply() && demo.accepts(message.accountId(), message.sender())) {
					latest.put(new DemoParticipant(message.accountId(), message.sender()), message);
				}
			}
			for (RemoteMessage message : received) {
				try {
					RemoteMessage selected = latest.get(new DemoParticipant(message.accountId(), message.sender()));
					boolean processReply = message.processReply() && (selected == null || selected.equals(message));
					boolean accepted = processReply
							? messages.acceptRemote(message.accountId(), message.receiveId(), message.sender(), message.content(), message.receivedAt().toEpochMilli())
							: messages.archiveRemote(message.accountId(), message.receiveId(), message.sender(), message.content(), message.receivedAt().toEpochMilli());
					result(accepted ? "accepted" : "duplicate");
					if (accepted) {
						String reason = processReply ? "eligible" : message.processReply() ? "superseded_in_snapshot" : message.archiveReason();
						LOGGER.info("SMS receive imported; accountId={}, receiveId={}, action={}, reason={}, gatewayReceivedAt={}, importedAt={}",
								message.accountId(), message.receiveId(), processReply ? "QUEUED" : "STORED", reason, message.receivedAt(), clock.instant());
					}
				} catch (SmsException exception) {
					if (exception.reason() == SmsException.Reason.CAPACITY) {
						failure("capacity", started);
						return;
					}
					result("conflicting_receipt");
					LOGGER.warn("SMS receive receipt conflict; accountId={}, receiveId={}", message.accountId(), message.receiveId());
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			result("interrupted");
		} catch (IOException exception) {
			failure("transport", started);
		} catch (RuntimeException exception) {
			failure("invalid_response_or_storage", started);
		}
	}

	private List<RemoteMessage> snapshot(String body) {
		JsonNode root = json.readTree(body);
		if (root == null || !root.isObject() || !root.path("success").isBoolean() || !root.path("success").booleanValue()
				|| !root.path("count").isIntegralNumber() || !root.path("count").canConvertToInt() || !root.path("messages").isArray()
				|| root.path("messages").size() > MAX_SNAPSHOT_MESSAGES
				|| root.path("count").longValue() != root.path("messages").size()) {
			throw new IllegalArgumentException("Invalid SMS HTTP API receive envelope");
		}
		List<RemoteMessage> received = new ArrayList<>();
		for (JsonNode entry : root.path("messages")) {
			try {
				RemoteMessage message = eligible(entry);
				if (message != null) received.add(message);
			} catch (IllegalArgumentException | DateTimeException | ArithmeticException | SmsException exception) {
				result("invalid_message");
			}
		}
		return received;
	}

	private RemoteMessage eligible(JsonNode entry) {
		Instant receivedAt = OffsetDateTime.parse(text(entry, "received_at")).toInstant();
		boolean fresh = receivedAt.toEpochMilli() >= receivedFrom;
		String device = text(entry, "goip_id");
		if (!entry.path("channel").isIntegralNumber() || !entry.path("channel").canConvertToInt()) throw new IllegalArgumentException("Invalid SMS line");
		int line = entry.path("channel").intValue();
		if (line < 1 || line > 8) throw new IllegalArgumentException("Invalid SMS line");
		List<SmsProperties.Channel> routes = sms.channels().stream()
				.filter(channel -> channel.deviceId().equals(device) && channel.simChannel() == line).toList();
		if (routes.size() != 1) { result("unconfigured_line"); return null; }
		SmsProperties.Channel route = routes.getFirst();
		if (route.simNumber() != null && !route.simNumber().isBlank()
				&& !route.simNumber().equals(SmsValidation.phone(text(entry, "sim_number")))) {
			result("sim_mismatch");
			return null;
		}
		String sender = text(entry, "from").trim();
		boolean phone = true;
		try { sender = SmsValidation.phone(sender); }
		catch (SmsException exception) { phone = false; }
		if (sender.isBlank() || sender.length() > 16 || sender.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Invalid inbound SMS sender");
		}
		boolean processReply = false;
		if (fresh && phone) {
			SmsTmbgDemoService demo = demos.getIfAvailable();
			processReply = demo != null && demo.accepts(route.accountId(), sender);
			if (!processReply) {
				var identity = customers.identity("SMS", route.accountId(), sender).orElse(null);
				processReply = identity != null && identity.verified() && identity.consent() && identity.outboundAllowed();
			}
		}
		long receiveId = Long.parseLong(text(entry, "recvid"));
		if (receiveId < 1) throw new IllegalArgumentException("Invalid SMS receive ID");
		String content = text(entry, "message");
		if (content.isBlank() || content.indexOf('\0') >= 0
				|| content.getBytes(StandardCharsets.UTF_8).length > sms.maxContentBytes()) {
			throw new IllegalArgumentException("Invalid inbound SMS content");
		}
		// raw_packet, gateway metadata, remote hashes and other subscribers' details never leave the snapshot parser.
		String archiveReason = !phone ? "non_phone_sender" : !fresh ? "before_receive_baseline" : "no_active_demo_or_authorized_identity";
		return new RemoteMessage(route.accountId(), receiveId, sender, content, receivedAt, processReply, archiveReason);
	}

	private static String text(JsonNode entry, String field) {
		if (!entry.isObject() || !entry.path(field).isString()) throw new IllegalArgumentException("Invalid SMS receive field");
		return entry.path(field).asString();
	}
	private void result(String outcome) { metrics.counter("sms.http_api.receive", "result", outcome).increment(); }
	private void failure(String category, long started) {
		result(category);
		LOGGER.warn("SMS HTTP API receive deferred; category={}, elapsedMs={}", category, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
	}

	@PreDestroy
	public void close() {
		ready = false;
		worker.shutdownNow();
	}

	private record DemoParticipant(String accountId, String sender) { }
	private record RemoteMessage(String accountId, long receiveId, String sender, String content, Instant receivedAt, boolean processReply, String archiveReason) {
		@Override public String toString() { return "RemoteSms[payload=<redacted>]"; }
	}
}
