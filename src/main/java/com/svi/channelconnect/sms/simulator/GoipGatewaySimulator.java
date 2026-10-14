package com.svi.channelconnect.sms.simulator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.goip.GoipPacket;
import com.svi.channelconnect.sms.goip.GoipPacketCodec;

/** Loopback-only protocol peer. It never has access to cellular hardware. */
public final class GoipGatewaySimulator implements AutoCloseable {
	public enum Behavior { SUCCESS, WAIT_THEN_OK, ERROR, AUTH_FAILURE, DROP_CHALLENGE, DROP_AUTH_CONFIRM, LOST_OK, SILENT_RESULT }
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private final Map<String, Peer> peers = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "goip-simulator-timer"); thread.setDaemon(true); return thread;
	});
	private final Duration inboundRetry;
	private final Duration sendDelay;
	private final AtomicLong receiveIds = new AtomicLong(100000);
	private volatile boolean closed;

	public GoipGatewaySimulator(List<SmsProperties.Channel> channels, InetSocketAddress server,
			Duration inboundRetry, Duration sendDelay) throws IOException {
		if (!server.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Simulator requires loopback");
		this.inboundRetry = inboundRetry; this.sendDelay = sendDelay;
		for (var channel : channels) {
			if (!SmsProperties.address(channel.sourceAddress()).isLoopbackAddress()) throw new IllegalArgumentException("Simulator requires loopback");
			Peer peer = new Peer(channel, server); peers.put(channel.accountId(), peer); peer.thread.start();
		}
		timer.scheduleWithFixedDelay(() -> peers.values().forEach(Peer::register), 0, 30, TimeUnit.SECONDS);
	}
	public void behavior(String account, Behavior behavior) { peer(account).behavior = behavior; }
	public int transmissions(String account) { return peer(account).transmissions.get(); }
	public int activeSessions(String account) { return peer(account).sessions.size(); }
	public int registrationAcks(String account) { return peer(account).registrationAcks.get(); }
	public void loseNextInboundAck(String account) { peer(account).loseInboundAck.set(true); }
	public void register(String account) { peer(account).register(); }
	public InetSocketAddress endpoint(String account) { return peer(account).localAddress(); }
	public CompletableFuture<Void> inbound(String account, long receiveId, String sender, String body) {
		Peer peer = peer(account);
		if (peer.inboundAcks.size() >= 1000) throw new IllegalArgumentException("Simulator capacity exhausted");
		if (receiveId < 1 || receiveId > Integer.MAX_VALUE || !sender.matches("\\+?[0-9]{8,15}")) throw new IllegalArgumentException("Invalid simulated SMS");
		String wire = "RECEIVE:" + receiveId + ";id:" + peer.channel.authId() + ";pass:" + peer.channel.password()
				+ ";srcnum:" + sender + ";msg:" + body;
		CompletableFuture<Void> ack = new CompletableFuture<>();
		peer.inboundAcks.put(receiveId, ack);
		for (int attempt = 0; attempt < 4; attempt++) {
			timer.schedule(() -> { if (!ack.isDone() && !closed) peer.write(wire); }, attempt * inboundRetry.toMillis(), TimeUnit.MILLISECONDS);
		}
		timer.schedule(() -> { if (peer.inboundAcks.remove(receiveId, ack)) ack.completeExceptionally(new IOException("Simulator acknowledgement timeout")); },
				4 * inboundRetry.toMillis(), TimeUnit.MILLISECONDS);
		return ack;
	}
	public CompletableFuture<Void> inbound(String account, String sender, String body) { return inbound(account, receiveIds.incrementAndGet(), sender, body); }
	/** Fault injection uses this peer's loopback socket, including deliberately malformed/authentication packets. */
	public void raw(String account, String wire) { peer(account).write(wire); }
	private Peer peer(String account) {
		Peer peer = peers.get(account); if (peer == null) throw new IllegalArgumentException("Unknown simulated account"); return peer;
	}
	@Override public void close() {
		closed = true; timer.shutdownNow();
		peers.values().forEach(p -> p.socket.close());
		for (Peer peer : peers.values()) {
			peer.inboundAcks.values().forEach(f -> f.completeExceptionally(new IOException("Simulator closed")));
			try { peer.thread.join(1000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
		}
	}
	private final class Peer {
		private final SmsProperties.Channel channel;
		private final InetSocketAddress server;
		private final DatagramSocket socket;
		private final Thread thread;
		private final Map<Long, Bulk> sessions = new ConcurrentHashMap<>();
		private final Map<Long, CompletableFuture<Void>> inboundAcks = new ConcurrentHashMap<>();
		private final AtomicInteger transmissions = new AtomicInteger();
		private final AtomicInteger registrationAcks = new AtomicInteger();
		private final AtomicInteger count = new AtomicInteger();
		private final java.util.concurrent.atomic.AtomicBoolean loseInboundAck = new java.util.concurrent.atomic.AtomicBoolean();
		private volatile Behavior behavior = Behavior.SUCCESS;
		private Peer(SmsProperties.Channel channel, InetSocketAddress server) throws IOException {
			this.channel = channel; this.server = server;
			socket = new DatagramSocket(new InetSocketAddress(SmsProperties.address(channel.sourceAddress()), 0));
			thread = new Thread(this::receive, "goip-simulator-" + channel.accountId()); thread.setDaemon(true);
		}
		private InetSocketAddress localAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
		private void register() {
			write("req:" + count.incrementAndGet() + ";id:" + channel.authId() + ";pass:" + channel.password()
					+ ";num:" + (channel.simNumber() == null ? "" : channel.simNumber()) + ";signal:23;gsm_status:LOGIN;voip_status:LOGOUT;");
		}
		private void write(String wire) {
			if (closed) return;
			try { byte[] bytes = wire.getBytes(StandardCharsets.UTF_8); socket.send(new DatagramPacket(bytes, bytes.length, server)); }
			catch (IOException exception) { if (!closed) throw new IllegalStateException("Simulator transport failure"); }
		}
		private void receive() {
			while (!closed) {
				DatagramPacket packet = new DatagramPacket(new byte[GoipPacketCodec.MAX_DATAGRAM_BYTES + 1], GoipPacketCodec.MAX_DATAGRAM_BYTES + 1);
				try {
					socket.receive(packet);
					if (!server.equals(packet.getSocketAddress())) continue;
					byte[] bytes = Arrays.copyOf(packet.getData(), packet.getLength());
					String wire = new String(bytes, StandardCharsets.UTF_8);
					if (wire.matches("reg:[0-9]+;status:0;")) { registrationAcks.incrementAndGet(); continue; }
					if (wire.matches("RECEIVE [0-9]+ OK\\n")) {
						if (loseInboundAck.getAndSet(false)) continue;
						long id = Long.parseLong(wire.split(" ")[1]);
						CompletableFuture<Void> ack = inboundAcks.remove(id); if (ack != null) ack.complete(null); continue;
					}
					handle(codec.decode(bytes));
				} catch (IOException exception) { if (closed) return; }
				catch (RuntimeException exception) { /* Malformed datagrams are dropped. */ }
			}
		}
		private void handle(GoipPacket packet) {
			long id = packet.id();
			if (packet.type() == GoipPacket.Type.MSG) {
				if (sessions.size() >= 5 || sessions.putIfAbsent(id, new Bulk()) != null) { write("ERROR " + id + " BUSY\n"); return; }
				timer.schedule(() -> sessions.remove(id), 90, TimeUnit.SECONDS);
				if (behavior != Behavior.DROP_CHALLENGE) write("PASSWORD " + id + "\n"); return;
			}
			if (packet.type() == GoipPacket.Type.DONE) { sessions.remove(id); write("DONE " + id + "\n"); return; }
			if (packet.type() == GoipPacket.Type.GSM_NUMBER || packet.type() == GoipPacket.Type.GSM_STATE) {
				if (!channel.password().equals(packet.body())) { write("ERROR " + id + " PASSWORD\n"); return; }
				write((packet.type() == GoipPacket.Type.GSM_NUMBER ? "get_gsm_num " + id + " " + (channel.simNumber() == null ? "" : channel.simNumber())
						: "get_gsm_state " + id + " IDLE")); return;
			}
			Bulk bulk = sessions.get(id); if (bulk == null) return;
			if (packet.type() == GoipPacket.Type.PASSWORD) {
				if (behavior == Behavior.AUTH_FAILURE || !channel.password().equals(packet.body())) { write("ERROR " + id + " PASSWORD\n"); return; }
				bulk.authenticated = true;
				if (behavior != Behavior.DROP_AUTH_CONFIRM) write("SEND " + id + "\n"); return;
			}
			if (packet.type() != GoipPacket.Type.SEND || packet.recipientId() == null || !bulk.authenticated) return;
			long sequence = packet.recipientId();
			String key = sequence + "/" + packet.body();
			if (bulk.numbers.containsKey(sequence) && !bulk.numbers.get(sequence).equals(packet.body())) { write("ERROR " + id + " " + sequence + " errorstatus:1\n"); return; }
			if (!bulk.status.containsKey(key)) {
				bulk.numbers.put(sequence, packet.body()); transmissions.incrementAndGet();
				bulk.status.put(key, "WAIT");
				if (behavior == Behavior.SILENT_RESULT) return;
				if (behavior == Behavior.ERROR) { bulk.status.put(key, "ERROR"); write("ERROR " + id + " " + sequence + " errorstatus:1\n"); return; }
				if (behavior == Behavior.WAIT_THEN_OK || !sendDelay.isZero()) {
					write("WAIT " + id + " " + sequence + "\n");
					timer.schedule(() -> { bulk.status.put(key, "OK"); if (behavior != Behavior.LOST_OK) write("OK " + id + " " + sequence + "\n"); },
							sendDelay.toMillis(), TimeUnit.MILLISECONDS); return;
				}
				bulk.status.put(key, "OK"); if (behavior == Behavior.LOST_OK) return;
			}
			if (behavior == Behavior.SILENT_RESULT) return;
			write(bulk.status.get(key) + " " + id + " " + sequence + (bulk.status.get(key).equals("ERROR") ? " errorstatus:1" : "") + "\n");
		}
	}
	private static final class Bulk {
		private volatile boolean authenticated;
		private final Map<Long, String> numbers = new ConcurrentHashMap<>();
		private final Map<String, String> status = new ConcurrentHashMap<>();
	}
}
