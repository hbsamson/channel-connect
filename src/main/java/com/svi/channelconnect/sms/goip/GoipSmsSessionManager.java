package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.provider.SmsProvider;

/** One recipient per bulk session: no status-cache eviction and no uncertain batch replay. */
public final class GoipSmsSessionManager {
	private final SmsProperties properties;
	private final GoipRegistrationService registrations;
	private final GoipUdpTransport transport;
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
	private final Map<String, Semaphore> devices = new ConcurrentHashMap<>();
	private final Map<String, Semaphore> channels = new ConcurrentHashMap<>();
	private final AtomicBoolean stopping = new AtomicBoolean();

	public GoipSmsSessionManager(SmsProperties properties, GoipRegistrationService registrations, GoipUdpTransport transport) {
		this.properties = properties; this.registrations = registrations; this.transport = transport;
		for (var c : properties.channels()) {
			devices.computeIfAbsent(c.deviceId(), ignored -> new Semaphore(properties.maxSessions()));
			channels.put(c.accountId(), new Semaphore(c.maxConcurrentSessions()));
		}
	}
	public void receive(GoipPacket packet, InetSocketAddress endpoint) {
		Session session = sessions.get(packet.id());
		if (session != null && session.registration.endpoint().equals(endpoint) && current(session)
				&& (packet.recipientId() == null || packet.recipientId() == 1L)) session.responses.offer(packet);
	}
	public SmsProvider.Result send(SmsOutbound message, Runnable beforeTransmission) {
		var channel = properties.channel(message.accountId());
		var registration = registrations.available(channel.accountId()).orElse(null);
		if (registration == null || stopping.get()) return result("QUEUED", "CHANNEL_UNAVAILABLE");
		Semaphore devicePermit = devices.get(channel.deviceId());
		Semaphore channelPermit = channels.get(channel.accountId());
		if (!devicePermit.tryAcquire()) return result("QUEUED", "BUSY");
		if (!channelPermit.tryAcquire()) { devicePermit.release(); return result("QUEUED", "BUSY"); }
		Session session = new Session(registration, System.nanoTime() + properties.sessionTimeout().toNanos());
		boolean transmitted = false;
		boolean initialized = false;
		if (sessions.putIfAbsent(message.sessionId(), session) != null) {
			channelPermit.release(); devicePermit.release(); return result("FAILED", "SESSION_COLLISION");
		}
		try {
			transport.send(codec.message(message.sessionId(), message.content()), registration.endpoint());
			initialized = true;
			GoipPacket challenge = await(session, GoipPacket.Type.PASSWORD, null, properties.packetTimeout());
			if (challenge == null) return result("FAILED", "INITIALIZATION_TIMEOUT");
			if (challenge.type() == GoipPacket.Type.ERROR) return result("FAILED", "INITIALIZATION_REJECTED");
			transport.send(codec.password(message.sessionId(), channel.password()), registration.endpoint());
			GoipPacket ready = await(session, GoipPacket.Type.SEND, null, properties.packetTimeout());
			if (ready == null) return result("FAILED", "AUTHENTICATION_TIMEOUT");
			if (ready.type() == GoipPacket.Type.ERROR) return result("FAILED", "AUTHENTICATION_REJECTED");
			if (!current(session)) return result("FAILED", "REGISTRATION_CHANGED");
			beforeTransmission.run();
			transmitted = true;
			byte[] recipient = codec.send(message.sessionId(), 1, message.recipient());
			transport.send(recipient, registration.endpoint());
			for (int poll = 0; poll <= properties.maxPolls(); poll++) {
				GoipPacket status = await(session, GoipPacket.Type.OK, 1L, properties.pollInterval());
				if (status != null && status.type() == GoipPacket.Type.OK) return result("GATEWAY_SENT", null);
				if (status != null && status.type() == GoipPacket.Type.ERROR) return result("FAILED", "GATEWAY_REJECTED");
				if (poll == properties.maxPolls() || !current(session) || System.nanoTime() >= session.deadline) break;
				// WAIT and lost replies are polled only with the unchanged tuple in the original live session.
				transport.send(recipient, registration.endpoint());
			}
			return result("UNKNOWN", "RESULT_TIMEOUT");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt(); return result(transmitted ? "UNKNOWN" : "FAILED", "INTERRUPTED");
		} catch (IOException exception) {
			return result(transmitted ? "UNKNOWN" : "FAILED", "TRANSPORT_FAILURE");
		} finally {
			if (initialized && current(session)) {
				try {
					transport.send(codec.done(message.sessionId()), registration.endpoint());
					await(session, GoipPacket.Type.DONE, null, properties.packetTimeout());
				} catch (IOException | InterruptedException exception) {
					if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
				}
			}
			sessions.remove(message.sessionId()); channelPermit.release(); devicePermit.release();
		}
	}
	private GoipPacket await(Session session, GoipPacket.Type expected, Long sequence, Duration timeout) throws InterruptedException {
		long deadline = Math.min(session.deadline, System.nanoTime() + timeout.toNanos());
		while (!stopping.get() && current(session)) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) return null;
			GoipPacket packet = session.responses.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
			if (packet == null) continue;
			if (java.util.Objects.equals(packet.recipientId(), sequence)
					&& (packet.type() == expected || packet.type() == GoipPacket.Type.ERROR)) return packet;
			// WAIT consumes time until the next poll, rather than triggering an immediate busy loop.
		}
		return null;
	}
	private boolean current(Session session) {
		return registrations.available(session.registration.accountId())
				.map(r -> r.generation() == session.registration.generation()).orElse(false);
	}
	public void stop() { stopping.set(true); }
	public int activeSessions() { return sessions.size(); }
	private SmsProvider.Result result(String status, String error) { return new SmsProvider.Result(status, error); }
	private static final class Session {
		private final GoipRegistrationService.Registration registration;
		private final long deadline;
		private final ArrayBlockingQueue<GoipPacket> responses = new ArrayBlockingQueue<>(16);
		private Session(GoipRegistrationService.Registration registration, long deadline) { this.registration = registration; this.deadline = deadline; }
	}
}
