package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.provider.SmsProvider;
import com.svi.channelconnect.sms.simulator.GoipGatewaySimulator;

@Component
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' != 'HTTP' && '${sms.mode:MOCK}' != 'HTTP_API'")
public class GoipSmsProvider implements SmsProvider, AutoCloseable {
	private final SmsProperties properties;
	private final SmsMessageRepository store;
	private final GoipRegistrationService registrations;
	private final GoipInboundPacketHandler inbound;
	private final MeterRegistry metrics;
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private final Map<Long, Query> queries = new ConcurrentHashMap<>();
	private final Semaphore queryPermits = new Semaphore(4);
	private volatile GoipUdpTransport transport;
	private volatile GoipSmsSessionManager sessions;
	private volatile GoipGatewaySimulator simulator;

	public GoipSmsProvider(SmsProperties properties, SmsMessageRepository store, MeterRegistry metrics, Clock clock, Environment environment) {
		this.properties = properties; this.store = store; this.metrics = metrics;
		if (properties.mode() == SmsProperties.Mode.MOCK && !environment.matchesProfiles("local", "test")) {
			throw new IllegalArgumentException("Mock GoIP requires local or test profile");
		}
		registrations = new GoipRegistrationService(properties, clock);
		inbound = new GoipInboundPacketHandler(registrations,
				new GoipInboundHandler(properties, store, registrations, metrics), metrics);
		metrics.gauge("sms.goip.sessions", this, p -> p.sessions == null ? 0 : p.sessions.activeSessions());
		metrics.gauge("sms.goip.available.channels", this, p -> p.statuses().stream().filter(GoipRegistrationService.Status::available).count());
	}
	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() throws IOException {
		if (transport != null) return;
		GoipUdpTransport opened = new GoipUdpTransport(new InetSocketAddress(SmsProperties.address(properties.bindAddress()), properties.port()), this::receive, metrics);
		transport = opened; sessions = new GoipSmsSessionManager(properties, registrations, opened); opened.start();
		try {
			if (properties.mode() == SmsProperties.Mode.MOCK) {
				simulator = new GoipGatewaySimulator(properties.channels(), opened.localAddress(), Duration.ofSeconds(3), Duration.ofMillis(100));
			}
		} catch (IOException | RuntimeException exception) { close(); throw exception; }
	}
	private void receive(byte[] bytes, InetSocketAddress source) {
		if (!registrations.allowedSource(source)) { metrics.counter("sms.goip.packets", "result", "source_denied").increment(); return; }
		GoipPacket packet = codec.decode(bytes);
		metrics.counter("sms.goip.packets", "result", "received").increment();
		try {
			if (inbound.receive(packet, source, transport)) return;
			Query query = queries.get(packet.id());
			if (query != null && query.registration.endpoint().equals(source)
					&& registrations.available(query.registration.accountId()).map(r -> r.generation() == query.registration.generation()).orElse(false)) {
				query.responses.offer(packet); return;
			}
			sessions.receive(packet, source);
		} catch (IOException exception) { throw new IllegalStateException("GoIP acknowledgement failed"); }
	}
	@Override public boolean available(String account) { return sessions != null && registrations.available(account).isPresent(); }
	@Override public Result send(SmsOutbound message, Runnable beforeTransmission) {
		if (sessions == null) return new Result("QUEUED", "NOT_STARTED");
		return sessions.send(message, beforeTransmission);
	}
	public List<GoipRegistrationService.Status> statuses() { return registrations.statuses(); }
	public GoipGatewaySimulator simulator() {
		if (simulator == null) throw new IllegalArgumentException("SMS simulator is not enabled"); return simulator;
	}
	public String query(String account, boolean number) throws IOException, InterruptedException {
		var registration = registrations.available(account).orElseThrow(() -> new IllegalArgumentException("SMS channel unavailable"));
		if (!queryPermits.tryAcquire()) throw new IllegalArgumentException("SMS query capacity exhausted");
		long id = -1;
		try {
			id = store.nextSessionId();
			Query query = new Query(registration); queries.put(id, query);
			byte[] request = codec.query(id, number, properties.channel(account).password());
			for (int attempt = 0; attempt < 2; attempt++) {
				transport.send(request, registration.endpoint());
				GoipPacket response = query.responses.poll(properties.packetTimeout().toMillis(), TimeUnit.MILLISECONDS);
				if (response != null && response.type() == (number ? GoipPacket.Type.GSM_NUMBER : GoipPacket.Type.GSM_STATE)) return response.body();
				if (response != null && response.type() == GoipPacket.Type.ERROR) throw new IOException("GoIP status query rejected");
			}
			throw new IOException("GoIP status query timeout");
		} finally { queries.remove(id); queryPermits.release(); }
	}
	public InetSocketAddress localAddress() {
		if (transport == null) throw new IllegalStateException("GoIP listener not started"); return transport.localAddress();
	}
	@PreDestroy
	@Override public synchronized void close() {
		if (sessions != null) sessions.stop();
		if (simulator != null) simulator.close();
		if (transport != null) transport.close(properties.shutdownTimeout());
	}
	private static final class Query {
		private final GoipRegistrationService.Registration registration;
		private final ArrayBlockingQueue<GoipPacket> responses = new ArrayBlockingQueue<>(4);
		private Query(GoipRegistrationService.Registration registration) { this.registration = registration; }
	}
}
