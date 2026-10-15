package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import tools.jackson.databind.ObjectMapper;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.sms.config.GoipHttpApiProperties;
import com.svi.channelconnect.sms.config.GoipWebInboxProperties;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsTmbgDemoService;
import com.svi.channelconnect.sms.service.SmsValidation;

/** Polls the read-only firmware inbox while leaving the gateway's SMS-server settings intact. */
@Component
@ConditionalOnProperty(name = {"sms.enabled", "sms.web-inbox.enabled"}, havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' == 'HTTP'")
public class GoipWebInboxPoller {
	private static final Logger LOGGER = LoggerFactory.getLogger(GoipWebInboxPoller.class);
	private final SmsProperties sms;
	private final GoipWebInboxProperties web;
	private final GoipHttpApiProperties limits;
	private final SmsMessageRepository messages;
	private final DealershipRepository customers;
	private final ObjectProvider<SmsTmbgDemoService> demos;
	private final MeterRegistry metrics;
	private final Clock clock;
	private final GoipWebInboxParser parser;
	private final HttpClient client;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("goip-web-inbox").factory());
	private final AtomicBoolean running = new AtomicBoolean();
	private final Map<String, Long> baselines = new HashMap<>();
	private volatile boolean ready;

	public GoipWebInboxPoller(SmsProperties sms, GoipWebInboxProperties web, GoipHttpApiProperties limits,
			SmsMessageRepository messages, DealershipRepository customers, ObjectProvider<SmsTmbgDemoService> demos,
			MeterRegistry metrics, Clock clock, ObjectMapper json) {
		this.sms = sms; this.web = web; this.limits = limits; this.messages = messages;
		this.customers = customers; this.demos = demos; this.metrics = metrics; this.clock = clock;
		parser = new GoipWebInboxParser(json);
		client = HttpClient.newBuilder().connectTimeout(sms.sessionTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		long initial = clock.instant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
		for (var channel : sms.channels()) {
			baselines.put(channel.accountId(), messages.receiveBaseline(
					SmsValidation.fingerprint("goip-web-inbox", channel.deviceId(), channel.accountId()), initial));
		}
		ready = true;
		LOGGER.info("GoIP web inbox polling enabled; channels={}", sms.channels().size());
	}

	@Scheduled(fixedDelayString = "${sms.poll-interval:PT3S}")
	public void tick() {
		if (!ready || !running.compareAndSet(false, true)) return;
		try {
			worker.execute(() -> {
				try { poll(); }
				finally { running.set(false); }
			});
		} catch (java.util.concurrent.RejectedExecutionException exception) { running.set(false); }
	}

	private void poll() {
		Map<String, SmsProperties.Channel> devices = new HashMap<>();
		for (var channel : sms.channels()) devices.putIfAbsent(channel.deviceId(), channel);
		for (var device : devices.values()) {
			try {
				var request = HttpRequest.newBuilder(new URI("http", null, device.sourceAddress(), -1,
						"/default/en_US/tools.html", "type=sms_inbox", null))
						.timeout(sms.sessionTimeout()).header("Cache-Control", "no-cache")
						.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
								(device.authId() + ":" + device.password()).getBytes(StandardCharsets.UTF_8))).GET().build();
				var pending = client.sendAsync(request, response -> new GoipHttpApiClient.LimitedBodySubscriber(limits.maxResponseBytes()));
				java.net.http.HttpResponse<byte[]> response;
				try { response = pending.get(sms.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS); }
				finally { if (!pending.isDone()) pending.cancel(true); }
				if (response.statusCode() != 200) {
					failure(response.statusCode() == 401 || response.statusCode() == 403 ? "authentication" : "http_status");
					continue;
				}
				String html = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
				importMessages(device.deviceId(), parser.parse(html, web.zone()));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception exception) {
				// Exception messages, URLs, HTML and credentials must not reach application logs.
				failure(exception instanceof IOException ? "transport" : "request_parse_or_storage");
			}
		}
	}

	private void importMessages(String deviceId, List<GoipWebInboxParser.Message> snapshot) {
		List<Receipt> receipts = new ArrayList<>();
		SmsTmbgDemoService demo = demos.getIfAvailable();
		for (var message : snapshot) {
			var channel = sms.channels().stream().filter(c -> c.deviceId().equals(deviceId) && c.simChannel() == message.line()).findFirst().orElse(null);
			if (channel == null) continue;
			String sender = message.sender();
			boolean phone = true;
			try { sender = SmsValidation.phone(sender); }
			catch (SmsException exception) { phone = false; }
			if (sender.isBlank() || sender.length() > 16 || sender.chars().anyMatch(Character::isISOControl)
					|| message.content().isBlank() || message.content().indexOf('\0') >= 0
					|| message.content().getBytes(StandardCharsets.UTF_8).length > sms.maxContentBytes()) {
				result("invalid_message");
				continue;
			}
			boolean demoReply = phone && demo != null
					&& (SmsTmbgDemoService.isReset(message.content()) || demo.accepts(channel.accountId(), sender));
			boolean process = false;
			if (phone && message.receivedAt() >= baselines.get(channel.accountId())) {
				var identity = customers.identity("SMS", channel.accountId(), sender).orElse(null);
				process = demoReply || identity != null && identity.verified() && identity.consent() && identity.outboundAllowed();
			}
			receipts.add(new Receipt(channel.accountId(), sender, message, process, demoReply));
		}
		receipts.sort(Comparator.comparingLong((Receipt r) -> r.message().receivedAt()).thenComparingLong(r -> r.message().id()));
		Map<Participant, Receipt> latest = new HashMap<>();
		for (var receipt : receipts) if (receipt.process() && receipt.demoReply()) latest.put(new Participant(receipt.account(), receipt.sender()), receipt);
		for (var receipt : receipts) {
			try {
				var message = receipt.message();
				var selected = latest.get(new Participant(receipt.account(), receipt.sender()));
				boolean process = receipt.process() && (selected == null || selected.equals(receipt));
				boolean fresh = process
						? messages.acceptRemote(receipt.account(), message.id(), receipt.sender(), message.content(), message.receivedAt())
						: messages.archiveRemote(receipt.account(), message.id(), receipt.sender(), message.content(), message.receivedAt());
				result(fresh ? "accepted" : "duplicate");
				if (fresh) LOGGER.info("GoIP web inbox imported; accountId={}, receiveId={}, action={}", receipt.account(), message.id(), process ? "QUEUED" : "STORED");
			} catch (SmsException exception) {
				if (exception.reason() == SmsException.Reason.CAPACITY) throw exception;
				result("conflicting_receipt");
			}
		}
	}

	private void result(String outcome) { metrics.counter("sms.web_inbox.receive", "result", outcome).increment(); }
	private void failure(String category) {
		result(category);
		LOGGER.warn("GoIP web inbox polling deferred; category={}", category);
	}

	@PreDestroy
	public void close() { ready = false; worker.shutdownNow(); client.close(); }

	private record Participant(String account, String sender) { }
	private record Receipt(String account, String sender, GoipWebInboxParser.Message message, boolean process, boolean demoReply) {
		@Override public String toString() { return "GoipInboxReceipt[content=<redacted>]"; }
	}
}
