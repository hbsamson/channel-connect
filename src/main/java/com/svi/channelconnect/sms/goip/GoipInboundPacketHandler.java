package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;

/** Common authenticated registration and durable receipt path for both outbound transports. */
final class GoipInboundPacketHandler {
	private final GoipRegistrationService registrations;
	private final GoipInboundHandler inbound;
	private final MeterRegistry metrics;
	private final GoipPacketCodec codec = new GoipPacketCodec();

	GoipInboundPacketHandler(GoipRegistrationService registrations, GoipInboundHandler inbound, MeterRegistry metrics) {
		this.registrations = registrations;
		this.inbound = inbound;
		this.metrics = metrics;
	}

	boolean receive(GoipPacket packet, InetSocketAddress source, GoipUdpTransport transport) throws IOException {
		if (packet.type() != GoipPacket.Type.REGISTRATION && packet.type() != GoipPacket.Type.RECEIVE) return false;
		var channel = registrations.authenticate(packet, source)
				.orElseThrow(() -> new IllegalArgumentException("GoIP authentication rejected"));
		if (packet.type() == GoipPacket.Type.RECEIVE) {
			inbound.receive(packet, channel, source, transport);
			return true;
		}
		if (!List.of("LOGIN", "LOGOUT").contains(packet.fields().get("gsm_status"))
				|| !packet.fields().getOrDefault("signal", "").matches("[0-9]{1,3}") || !packet.fields().containsKey("num")) {
			throw new IllegalArgumentException("Invalid GoIP registration");
		}
		transport.send(codec.registrationAck(packet.id()), source);
		registrations.registered(channel, packet, source);
		metrics.counter("sms.goip.registrations", "result", "acknowledged").increment();
		return true;
	}
}
