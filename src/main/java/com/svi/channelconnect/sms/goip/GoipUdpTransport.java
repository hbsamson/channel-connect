package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BiConsumer;

import io.micrometer.core.instrument.MeterRegistry;

/** One bounded datagram receiver. Durable receipt is synchronous; AI never runs on this thread. */
public final class GoipUdpTransport implements AutoCloseable {
	private final DatagramSocket socket;
	private final Thread receiver;
	private final MeterRegistry metrics;
	private volatile boolean running;

	public GoipUdpTransport(InetSocketAddress bind, BiConsumer<byte[], InetSocketAddress> handler,
			MeterRegistry metrics) throws SocketException {
		this.metrics = metrics;
		socket = new DatagramSocket(bind);
		receiver = new Thread(() -> receive(handler), "goip-udp-receiver");
		receiver.setDaemon(true);
	}
	public void start() { running = true; receiver.start(); }
	public InetSocketAddress localAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
	public void send(byte[] bytes, InetSocketAddress endpoint) throws IOException {
		if (bytes.length > GoipPacketCodec.MAX_DATAGRAM_BYTES) throw new IllegalArgumentException("GoIP datagram too large");
		socket.send(new DatagramPacket(bytes, bytes.length, endpoint));
	}
	private void receive(BiConsumer<byte[], InetSocketAddress> handler) {
		while (running) {
			byte[] buffer = new byte[GoipPacketCodec.MAX_DATAGRAM_BYTES + 1];
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			try {
				socket.receive(packet);
				if (packet.getLength() > GoipPacketCodec.MAX_DATAGRAM_BYTES) {
					metrics.counter("sms.goip.packets", "result", "oversized").increment(); continue;
				}
				handler.accept(Arrays.copyOf(buffer, packet.getLength()), (InetSocketAddress) packet.getSocketAddress());
			} catch (IOException exception) {
				if (running) metrics.counter("sms.goip.packets", "result", "io_failure").increment();
			} catch (RuntimeException exception) {
				// No raw packet, credential, phone number, SQL parameter, or exception message is logged.
				metrics.counter("sms.goip.packets", "result", "rejected").increment();
			}
		}
	}
	public void close(Duration timeout) {
		running = false; socket.close();
		try { receiver.join(timeout.toMillis()); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
	}
	@Override public void close() { close(Duration.ofSeconds(2)); }
}
