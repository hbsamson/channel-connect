package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.svi.channelconnect.sms.config.GoipHttpInboundProperties;
import com.svi.channelconnect.sms.config.GoipHttpInboundProperties.ChannelCredentials;
import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.provider.SmsProvider;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipHttpInboundListenerTests {
	@Test
	void receivesTwoAuthenticatedLinesFromOneGatewaySocketWithSeparateHttpAndUdpCredentials() throws Exception {
		int port = availablePort();
		// Start the receiver directly with synthetic loopback configuration; Spring mode selection is tested below.
		var properties = loopbackProperties(port);
		var store = mock(SmsMessageRepository.class);
		when(store.accept("sim-1", 201, "+639000000001", "Book tomorrow.")).thenReturn(true);
		when(store.accept("sim-2", 202, "+639000000002", "What are your hours?")).thenReturn(true);
		try (var listener = new GoipHttpInboundListener(properties, inboundProperties(), store,
				new SimpleMeterRegistry(), Clock.systemUTC());
				var gateway = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
			listener.start();
			gateway.setSoTimeout(2000);
			send(gateway, port, "req:1;id:udp-id-1;pass:udp-password-1;num:;signal:23;gsm_status:LOGIN;");
			assertThat(receive(gateway)).isEqualTo("reg:1;status:0;");
			send(gateway, port, "req:2;id:udp-id-2;pass:udp-password-2;num:;signal:23;gsm_status:LOGIN;");
			assertThat(receive(gateway)).isEqualTo("reg:2;status:0;");

			send(gateway, port, "RECEIVE:201;id:udp-id-1;pass:udp-password-1;srcnum:+639000000001;msg:Book tomorrow.");
			assertThat(receive(gateway)).isEqualTo("RECEIVE 201 OK\n");
			send(gateway, port, "RECEIVE:202;id:udp-id-2;pass:udp-password-2;srcnum:+639000000002;msg:What are your hours?");
			assertThat(receive(gateway)).isEqualTo("RECEIVE 202 OK\n");
			assertThat(listener.statuses()).allMatch(GoipRegistrationService.Status::available);
			verify(store).accept("sim-1", 201, "+639000000001", "Book tomorrow.");
			verify(store).accept("sim-2", 202, "+639000000002", "What are your hours?");
		}
	}

	@Test
	void httpSenderAndOptInInboundListenerCoexistWithoutTwoProviders() {
		context().withPropertyValues("sms.enabled=true", "sms.mode=HTTP", "sms.http-inbound.enabled=true")
				.run(context -> {
					assertThat(context).hasSingleBean(SmsProvider.class).hasSingleBean(GoipHttpInboundListener.class);
					assertThat(context.getBean(SmsProvider.class)).isInstanceOf(GoipHttpSmsProvider.class);
					assertThat(context.getBean(GoipHttpInboundListener.class).statuses())
							.extracting(GoipRegistrationService.Status::accountId).containsExactly("sim-1", "sim-2");
				});
	}

	@Test
	void httpReceiveListenerRemainsDisabledUntilExplicitlyEnabled() {
		context().withPropertyValues("sms.enabled=true", "sms.mode=HTTP")
				.run(context -> assertThat(context).hasSingleBean(GoipHttpSmsProvider.class)
						.doesNotHaveBean(GoipHttpInboundListener.class));
	}

	@Test
	void httpReceiveListenerDoesNotStartForUdpMode() {
		context().withPropertyValues("sms.enabled=true", "sms.mode=MOCK", "sms.http-inbound.enabled=true")
				.run(context -> assertThat(context).doesNotHaveBean(GoipHttpInboundListener.class));
	}

	@Test
	void rejectsAmbiguousInboundIdsEvenWhenHttpLoginIsSharedAcrossLines() {
		var credentials = new GoipHttpInboundProperties(true, List.of(
				new ChannelCredentials("sim-1", "same-udp-id", "udp-password-1"),
				new ChannelCredentials("sim-2", "same-udp-id", "udp-password-2")));
		assertThatThrownBy(() -> listener(httpProperties(), credentials))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique UDP authentication ID");
	}

	@Test
	void rejectsUnknownInboundAccountAndImplicitListenerPort() {
		var unknown = new GoipHttpInboundProperties(true,
				List.of(new ChannelCredentials("unknown", "udp-id", "udp-password")));
		assertThatThrownBy(() -> listener(httpProperties(), unknown))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown SMS account");
		var properties = SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193"));
		assertThatThrownBy(() -> listener(properties, inboundProperties()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicit private UDP bind address and port");
	}

	@Test
	void validatesReceiveCredentialsAndRedactsTheirRendering() {
		assertThatThrownBy(() -> new GoipHttpInboundProperties(true, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ChannelCredentials("sim-1", "udp-id", "has:semicolon"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(inboundProperties().toString()).doesNotContain("udp-password");
		assertThat(inboundProperties().channels().getFirst().toString()).doesNotContain("udp-password", "udp-id");
	}

	private ApplicationContextRunner context() {
		return new ApplicationContextRunner().withBean(SmsProperties.class, GoipHttpInboundListenerTests::httpProperties)
				.withBean(GoipHttpInboundProperties.class, GoipHttpInboundListenerTests::inboundProperties)
				.withBean(SmsMessageRepository.class, () -> mock(SmsMessageRepository.class))
				.withBean(MeterRegistry.class, SimpleMeterRegistry::new).withBean(Clock.class, Clock::systemUTC)
				.withUserConfiguration(GoipHttpSmsProvider.class, GoipHttpInboundListener.class);
	}

	private static SmsProperties httpProperties() {
		return SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true", "sms.port", "44444",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193",
				"sms.channels[0].auth-id", "web-user", "sms.channels[1].auth-id", "web-user"));
	}

	private static GoipHttpInboundProperties inboundProperties() {
		return new GoipHttpInboundProperties(true, List.of(new ChannelCredentials("sim-1", "udp-id-1", "udp-password-1"),
				new ChannelCredentials("sim-2", "udp-id-2", "udp-password-2")));
	}

	private static GoipHttpInboundListener listener(SmsProperties properties, GoipHttpInboundProperties credentials) {
		return new GoipHttpInboundListener(properties, credentials, mock(SmsMessageRepository.class),
				new SimpleMeterRegistry(), Clock.systemUTC());
	}

	private static SmsProperties loopbackProperties(int port) {
		return SmsTestProperties.properties(Map.of("sms.enabled", "false", "sms.mode", "HTTP", "sms.port", Integer.toString(port),
				"sms.channels[0].auth-id", "web-user", "sms.channels[1].auth-id", "web-user"));
	}
	private static int availablePort() throws Exception {
		try (var socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { return socket.getLocalPort(); }
	}
	private static void send(DatagramSocket socket, int port, String wire) throws Exception {
		byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
		socket.send(new DatagramPacket(bytes, bytes.length, new InetSocketAddress("127.0.0.1", port)));
	}
	private static String receive(DatagramSocket socket) throws Exception {
		var packet = new DatagramPacket(new byte[1024], 1024);
		socket.receive(packet);
		return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
	}
}
