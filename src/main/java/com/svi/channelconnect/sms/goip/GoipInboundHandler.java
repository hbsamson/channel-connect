package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.MeterRegistry;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsValidation;

public final class GoipInboundHandler {
	private final SmsProperties properties;
	private final SmsMessageRepository store;
	private final GoipRegistrationService registrations;
	private final MeterRegistry metrics;
	private final GoipPacketCodec codec = new GoipPacketCodec();
	public GoipInboundHandler(SmsProperties properties, SmsMessageRepository store, GoipRegistrationService registrations, MeterRegistry metrics) {
		this.properties = properties; this.store = store; this.registrations = registrations; this.metrics = metrics;
	}
	public void receive(GoipPacket packet, SmsProperties.Channel channel, InetSocketAddress source, GoipUdpTransport transport) throws IOException {
		var registration = registrations.available(channel.accountId()).orElse(null);
		if (registration == null || !registration.endpoint().equals(source)) throw new IllegalArgumentException("Unregistered SMS channel");
		String body = packet.body();
		if (body == null || body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > properties.maxContentBytes()) {
			throw new IllegalArgumentException("Invalid inbound SMS content");
		}
		String sender = SmsValidation.phone(packet.fields().get("srcnum"));
		boolean fresh = store.accept(channel.accountId(), packet.id(), sender, body);
		// The proxied repository transaction has committed before this datagram is sent.
		transport.send(codec.receiveAck(packet.id()), source);
		metrics.counter("sms.inbound", "result", fresh ? "accepted" : "duplicate").increment();
	}
}
