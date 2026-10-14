package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipInboundPacketHandlerTests {
	private final SmsMessageRepository store = mock(SmsMessageRepository.class);
	private final GoipUdpTransport transport = mock(GoipUdpTransport.class);
	private final InetSocketAddress source = new InetSocketAddress("127.0.0.1", 49152);
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private final com.svi.channelconnect.sms.config.SmsProperties properties = SmsTestProperties.properties(Map.of());
	private final GoipRegistrationService registrations = new GoipRegistrationService(properties, Clock.systemUTC());
	private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
	private final GoipInboundPacketHandler handler = new GoipInboundPacketHandler(registrations,
			new GoipInboundHandler(properties, store, registrations, metrics), metrics);

	@Test
	void persistsTheReplyBeforeAcknowledgingIt() throws Exception {
		register();
		clearInvocations(transport);
		when(store.accept("sim-1", 101, "+639000000001", "Please book tomorrow.")).thenReturn(true);
		assertThat(handler.receive(receive("test-password"), source, transport)).isTrue();
		var order = inOrder(store, transport);
		order.verify(store).accept("sim-1", 101, "+639000000001", "Please book tomorrow.");
		order.verify(transport).send(codec.receiveAck(101), source);
	}

	@Test
	void rejectsIncorrectCredentialsAndUnregisteredReplies() {
		assertThatThrownBy(() -> handler.receive(receive("wrong-password"), source, transport))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("GoIP authentication rejected");
		assertThatThrownBy(() -> handler.receive(receive("test-password"), source, transport))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Unregistered SMS channel");
		verifyNoInteractions(store, transport);
	}

	@Test
	void doesNotAcknowledgeReceiptWhenPersistenceFails() throws Exception {
		register();
		clearInvocations(transport);
		doThrow(new IllegalStateException("Persistence unavailable"))
				.when(store).accept("sim-1", 101, "+639000000001", "Please book tomorrow.");
		assertThatThrownBy(() -> handler.receive(receive("test-password"), source, transport))
				.isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(transport);
	}

	@Test
	void doesNotMakeAChannelAvailableWhenRegistrationAcknowledgementFails() throws Exception {
		doThrow(new IOException("Synthetic send failure")).when(transport).send(codec.registrationAck(1), source);
		assertThatThrownBy(this::register).isInstanceOf(IOException.class);
		assertThat(registrations.available("sim-1")).isEmpty();
	}

	private void register() throws IOException {
		handler.receive(packet("req:1;id:test-id-1;pass:test-password;num:;signal:23;gsm_status:LOGIN;"), source, transport);
	}
	private GoipPacket receive(String password) {
		return packet("RECEIVE:101;id:test-id-1;pass:" + password + ";srcnum:+639000000001;msg:Please book tomorrow.");
	}
	private GoipPacket packet(String wire) { return codec.decode(wire.getBytes(StandardCharsets.UTF_8)); }
}
