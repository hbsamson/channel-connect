package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipRegistrationServiceTests {
	@Test void authenticatesTracksExpiryAndDistinguishesEndpointAndRebootGenerations() {
		var properties = SmsTestProperties.properties(Map.of());
		var now = new AtomicLong(100000);
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
		};
		var service = new GoipRegistrationService(properties, clock);
		var source = new InetSocketAddress("127.0.0.1", 1234);
		var packet = packet(10, "test-id-1", "test-password");
		assertThat(service.available("sim-1")).isEmpty();
		assertThat(service.authenticate(packet, source)).contains(properties.channel("sim-1"));
		assertThat(service.authenticate(packet(10, "test-id-1", "wrong"), source)).isEmpty();
		assertThat(service.authenticate(packet, new InetSocketAddress("127.0.0.2", 1234))).isEmpty();
		service.registered(properties.channel("sim-1"), packet, source);
		long generation = service.available("sim-1").orElseThrow().generation();
		service.registered(properties.channel("sim-1"), packet(11, "test-id-1", "test-password"), source);
		assertThat(service.available("sim-1").orElseThrow().generation()).isEqualTo(generation);
		service.registered(properties.channel("sim-1"), packet(1, "test-id-1", "test-password"), source);
		assertThat(service.available("sim-1").orElseThrow().generation()).isGreaterThan(generation);
		now.addAndGet(90000); assertThat(service.available("sim-1")).isEmpty();
	}
	@Test void refusesSimSelectionWhenTwoAuthenticatedIdentitiesShareAnEndpoint() {
		var properties = SmsTestProperties.properties(Map.of());
		var service = new GoipRegistrationService(properties, Clock.systemUTC());
		var source = new InetSocketAddress("127.0.0.1", 1234);
		service.registered(properties.channel("sim-1"), packet(1, "test-id-1", "test-password"), source);
		service.registered(properties.channel("sim-2"), packet(1, "test-id-2", "test-password"), source);
		assertThat(service.available("sim-1")).isEmpty(); assertThat(service.available("sim-2")).isEmpty();
	}
	private GoipPacket packet(int count, String id, String password) {
		return new GoipPacketCodec().decode(("req:" + count + ";id:" + id + ";pass:" + password + ";num:;signal:23;gsm_status:LOGIN;voip_status:LOGOUT;").getBytes(StandardCharsets.UTF_8));
	}
}
