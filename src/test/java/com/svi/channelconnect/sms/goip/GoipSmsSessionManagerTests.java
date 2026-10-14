package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.simulator.GoipGatewaySimulator.Behavior;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipSmsSessionManagerTests {
	@Test void boundsPhysicalDeviceConcurrencyAcrossDifferentSimEndpoints() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.max-sessions", "1", "sms.packet-timeout", "500ms", "sms.poll-interval", "200ms", "sms.session-timeout", "4s"));
		var env = new StandardEnvironment(); env.setActiveProfiles("test");
		try (var provider = new GoipSmsProvider(properties, mock(SmsMessageRepository.class), new SimpleMeterRegistry(), Clock.systemUTC(), env);
				var executor = Executors.newSingleThreadExecutor()) {
			provider.start(); await(() -> provider.available("sim-1") && provider.available("sim-2"));
			provider.simulator().behavior("sim-1", Behavior.SILENT_RESULT);
			var first = executor.submit(() -> provider.send(message(11, "sim-1"), () -> { }));
			await(() -> provider.simulator().transmissions("sim-1") == 1);
			assertThat(provider.send(message(12, "sim-2"), () -> fail("Second recipient must not be submitted")).status()).isEqualTo("QUEUED");
			assertThat(provider.simulator().transmissions("sim-2")).isZero();
			provider.simulator().raw("sim-1", "OK 11 1\n");
			assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("GATEWAY_SENT");
			assertThat(provider.simulator().activeSessions("sim-1")).isZero();
		}
	}
	@Test void enforcesAbsoluteSessionDeadlineEvenWhenMorePollsAreConfigured() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.max-polls", "100", "sms.packet-timeout", "100ms", "sms.poll-interval", "100ms", "sms.session-timeout", "350ms"));
		var env = new StandardEnvironment(); env.setActiveProfiles("test");
		try (var provider = new GoipSmsProvider(properties, mock(SmsMessageRepository.class), new SimpleMeterRegistry(), Clock.systemUTC(), env)) {
			provider.start(); await(() -> provider.available("sim-1"));
			provider.simulator().behavior("sim-1", Behavior.SILENT_RESULT);
			long began = System.nanoTime();
			assertThat(provider.send(message(13, "sim-1"), () -> { }).status()).isEqualTo("UNKNOWN");
			assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).isLessThan(1500);
			assertThat(provider.simulator().transmissions("sim-1")).isEqualTo(1);
		}
	}
	@Test void appliesConfiguredPerSimConcurrencyWithoutExceedingTheDeviceLimit() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.channels[0].max-concurrent-sessions", "2", "sms.packet-timeout", "500ms", "sms.poll-interval", "200ms", "sms.session-timeout", "4s"));
		var env = new StandardEnvironment(); env.setActiveProfiles("test");
		try (var provider = new GoipSmsProvider(properties, mock(SmsMessageRepository.class), new SimpleMeterRegistry(), Clock.systemUTC(), env);
				var executor = Executors.newFixedThreadPool(2)) {
			provider.start(); await(() -> provider.available("sim-1"));
			provider.simulator().behavior("sim-1", Behavior.SILENT_RESULT);
			var first = executor.submit(() -> provider.send(message(21, "sim-1"), () -> { }));
			var second = executor.submit(() -> provider.send(message(22, "sim-1"), () -> { }));
			await(() -> provider.simulator().transmissions("sim-1") == 2);
			assertThat(provider.send(message(23, "sim-1"), () -> fail("Per-SIM limit must hold")).status()).isEqualTo("QUEUED");
			provider.simulator().raw("sim-1", "OK 21 1\n"); provider.simulator().raw("sim-1", "OK 22 1\n");
			assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("GATEWAY_SENT");
			assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo("GATEWAY_SENT");
		}
	}
	private SmsOutbound message(long session, String account) { return new SmsOutbound("test-" + session, account, "+639000000001", "Protocol test", session, 1, "QUEUED", "test", null); }
	private void await(java.util.function.BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
		assertThat(condition.getAsBoolean()).isTrue();
	}
}
