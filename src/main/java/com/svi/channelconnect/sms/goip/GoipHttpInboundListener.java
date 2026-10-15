package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.sms.config.GoipHttpInboundProperties;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;

/** GoIP documents UDP reception even when outbound messages use its HTTP web form. */
@Component
@ConditionalOnProperty(name = {"sms.enabled", "sms.http-inbound.enabled"}, havingValue = "true")
@ConditionalOnExpression("('${sms.mode:MOCK}' == 'HTTP' || '${sms.mode:MOCK}' == 'HTTP_API') && !${sms.web-inbox.enabled:false}")
public class GoipHttpInboundListener implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(GoipHttpInboundListener.class);
	private final SmsProperties properties;
	private final GoipRegistrationService registrations;
	private final GoipInboundPacketHandler inbound;
	private final MeterRegistry metrics;
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private volatile GoipUdpTransport transport;

	public GoipHttpInboundListener(SmsProperties properties, GoipHttpInboundProperties inboundProperties,
			SmsMessageRepository store, MeterRegistry metrics, Clock clock) {
		this.properties = properties;
		this.metrics = metrics;
		var bind = SmsProperties.address(properties.bindAddress());
		if (properties.port() == 0 || bind.isAnyLocalAddress() || !(bind.isSiteLocalAddress() || bind.isLoopbackAddress())) {
			throw new IllegalArgumentException("HTTP inbound SMS requires an explicit private UDP bind address and port");
		}
		Set<String> identities = new HashSet<>();
		List<SmsProperties.Channel> channels = inboundProperties.channels().stream().map(credentials -> {
			SmsProperties.Channel channel = properties.channel(credentials.accountId());
			var sourceAddress = SmsProperties.address(channel.sourceAddress());
			if (!sourceAddress.isSiteLocalAddress() && !sourceAddress.isLoopbackAddress()) {
				throw new IllegalArgumentException("HTTP inbound SMS requires a private gateway source address");
			}
			if (!identities.add(sourceAddress.getHostAddress() + "/" + credentials.authId())) {
				throw new IllegalArgumentException("HTTP inbound SMS requires a unique UDP authentication ID per gateway line");
			}
			return new SmsProperties.Channel(channel.accountId(), channel.deviceId(), channel.simChannel(), channel.sourceAddress(),
					credentials.authId(), credentials.password(), channel.simNumber(), channel.sendInterval(),
					channel.sendsPerHour(), channel.maxConcurrentSessions());
		}).toList();
		registrations = new GoipRegistrationService(channels, properties.registrationTimeout(), clock, false);
		inbound = new GoipInboundPacketHandler(registrations,
				new GoipInboundHandler(properties, store, registrations, metrics), metrics);
		metrics.gauge("sms.goip.inbound.available.channels", this,
				listener -> listener.statuses().stream().filter(GoipRegistrationService.Status::available).count());
	}

	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() throws IOException {
		if (transport != null) return;
		transport = new GoipUdpTransport(new InetSocketAddress(SmsProperties.address(properties.bindAddress()), properties.port()),
				this::receive, metrics);
		transport.start();
		LOGGER.info("Direct GoIP SMS receiver started; channels={}, hostedReceivePolling=false", registrations.statuses().size());
	}

	private void receive(byte[] bytes, InetSocketAddress source) {
		if (!registrations.allowedSource(source)) {
			metrics.counter("sms.goip.packets", "result", "source_denied").increment();
			return;
		}
		GoipPacket packet = codec.decode(bytes);
		metrics.counter("sms.goip.packets", "result", "received").increment();
		try {
			inbound.receive(packet, source, transport);
		} catch (IOException exception) {
			throw new IllegalStateException("GoIP acknowledgement failed", exception);
		}
	}

	public List<GoipRegistrationService.Status> statuses() { return registrations.statuses(); }
	@PreDestroy
	@Override public synchronized void close() {
		if (transport != null) transport.close(properties.shutdownTimeout());
	}
}
