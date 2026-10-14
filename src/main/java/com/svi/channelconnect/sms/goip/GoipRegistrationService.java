package com.svi.channelconnect.sms.goip;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.svi.channelconnect.sms.config.SmsProperties;

public final class GoipRegistrationService {
	private final SmsProperties properties;
	private final Clock clock;
	private final Map<String, Registration> registrations = new HashMap<>();
	private long generation;
	public GoipRegistrationService(SmsProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

	public Optional<SmsProperties.Channel> authenticate(GoipPacket packet, InetSocketAddress source) {
		String id = packet.fields().get("id");
		String password = packet.fields().getOrDefault("pass", packet.fields().get("password"));
		return properties.channels().stream().filter(c -> SmsProperties.address(c.sourceAddress()).equals(source.getAddress())
				&& c.authId().equals(id) && equal(c.password(), password)).findFirst();
	}
	public boolean allowedSource(InetSocketAddress source) {
		return properties.channels().stream().anyMatch(c -> SmsProperties.address(c.sourceAddress()).equals(source.getAddress()));
	}
	/** Called only after authenticating req and successfully sending its matching acknowledgement. */
	public synchronized void registered(SmsProperties.Channel channel, GoipPacket packet, InetSocketAddress endpoint) {
		String gsm = packet.fields().get("gsm_status");
		if (!List.of("LOGIN", "LOGOUT").contains(gsm)) throw new IllegalArgumentException("Invalid GSM registration state");
		String signal = packet.fields().get("signal");
		if (signal == null || !signal.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid GSM signal");
		Registration previous = registrations.get(channel.accountId());
		boolean changed = previous == null || !previous.endpoint().equals(endpoint) || packet.id() < previous.count()
				|| clock.millis() - previous.lastSeen() >= properties.registrationTimeout().toMillis();
		long epoch = changed ? ++generation : previous.generation();
		boolean numberMatches = channel.simNumber() == null || channel.simNumber().isBlank()
				|| channel.simNumber().equals(packet.fields().get("num"));
		registrations.put(channel.accountId(), new Registration(channel.accountId(), endpoint, packet.id(), clock.millis(), epoch,
				"LOGIN".equals(gsm) && numberMatches, Integer.parseInt(signal)));
	}
	public synchronized Optional<Registration> available(String account) {
		Registration value = registrations.get(account);
		if (value == null || !value.gsmReady() || clock.millis() - value.lastSeen() >= properties.registrationTimeout().toMillis()) return Optional.empty();
		// Two configured channels on the same endpoint cannot be selected by this protocol.
		if (registrations.values().stream().anyMatch(r -> !r.accountId().equals(account) && r.endpoint().equals(value.endpoint())
				&& clock.millis() - r.lastSeen() < properties.registrationTimeout().toMillis())) return Optional.empty();
		return Optional.of(value);
	}
	public synchronized List<Status> statuses() {
		return properties.channels().stream().map(c -> {
			Registration r = registrations.get(c.accountId());
			return new Status(c.accountId(), c.simChannel(), available(c.accountId()).isPresent(), r == null ? null : r.lastSeen(),
					r == null ? null : r.signal());
		}).toList();
	}
	private static boolean equal(String expected, String actual) {
		return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}
	public record Registration(String accountId, InetSocketAddress endpoint, long count, long lastSeen,
			long generation, boolean gsmReady, int signal) { }
	public record Status(String accountId, int simChannel, boolean available, Long lastSeen, Integer signal) { }
}
