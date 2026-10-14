package com.svi.channelconnect.sms.config;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sms")
public record SmsProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("MOCK") Mode mode,
		@DefaultValue("127.0.0.1") String bindAddress,
		@DefaultValue("0") int port,
		@DefaultValue("false") boolean liveAuthorized,
		@DefaultValue("") String apiToken,
		List<Channel> channels,
		@DefaultValue("90s") Duration registrationTimeout,
		@DefaultValue("80s") Duration sessionTimeout,
		@DefaultValue("3s") Duration packetTimeout,
		@DefaultValue("3s") Duration pollInterval,
		@DefaultValue("10") int maxPolls,
		@DefaultValue("5") int maxSessions,
		@DefaultValue("1000") int queueCapacity,
		@DefaultValue("3000") int maxContentBytes,
		@DefaultValue("1") int maxSegments,
		@DefaultValue("3") int processingAttempts,
		@DefaultValue("1s") Duration processingRetryDelay,
		@DefaultValue("24h") Duration queueExpiry,
		@DefaultValue("30d") Duration contentRetention,
		@DefaultValue("90d") Duration idRetention,
		@DefaultValue("10s") Duration shutdownTimeout) {
	public enum Mode { MOCK, LIVE, HTTP }

	public SmsProperties {
		channels = channels == null ? List.of() : List.copyOf(channels);
		if (mode == null || port < 0 || port > 65535 || maxSessions < 1 || maxSessions > 5
				|| queueCapacity < 1 || queueCapacity > 100000 || maxContentBytes < 1 || maxContentBytes > 3000
				|| maxSegments < 1 || maxSegments > 10 || maxPolls < 0 || maxPolls > 100
				|| processingAttempts < 1 || processingAttempts > 10) invalid();
		for (Duration duration : List.of(registrationTimeout, sessionTimeout, packetTimeout, pollInterval,
				processingRetryDelay, queueExpiry, contentRetention, idRetention, shutdownTimeout)) {
			if (duration.isNegative() || duration.isZero()) invalid();
		}
		if (sessionTimeout.compareTo(Duration.ofSeconds(90)) > 0 || idRetention.compareTo(contentRetention) < 0) invalid();
		if (enabled) {
			if (channels.isEmpty() || channels.size() > 64) invalid();
			Set<String> accounts = new HashSet<>();
			Set<String> identities = new HashSet<>();
			Set<String> simMappings = new HashSet<>();
			java.util.Map<String, String> deviceAddresses = new java.util.HashMap<>();
			for (Channel channel : channels) {
				if (!accounts.add(channel.accountId()) || (mode != Mode.HTTP && !identities.add(address(channel.sourceAddress()).getHostAddress() + "/" + channel.authId()))
						|| !simMappings.add(channel.deviceId() + "/" + channel.simChannel())) invalid();
				String previous = deviceAddresses.putIfAbsent(channel.deviceId(), address(channel.sourceAddress()).getHostAddress());
				if (previous != null && !previous.equals(address(channel.sourceAddress()).getHostAddress())) invalid();
				if (mode == Mode.MOCK && !address(channel.sourceAddress()).isLoopbackAddress()) invalid();
			}
			InetAddress bind = address(bindAddress);
			if (mode == Mode.MOCK && !bind.isLoopbackAddress()) invalid();
			if (mode == Mode.LIVE && (!liveAuthorized || port == 0 || bind.isAnyLocalAddress()
					|| !(bind.isSiteLocalAddress() || bind.isLoopbackAddress()) || maxSegments != 1)) invalid();
			if (mode == Mode.LIVE && channels.stream().anyMatch(c -> !address(c.sourceAddress()).isSiteLocalAddress())) invalid();
			if (mode == Mode.LIVE && channels.stream().anyMatch(c -> c.maxConcurrentSessions() != 1)) invalid();
			if (mode == Mode.HTTP && (!liveAuthorized || channels.stream().anyMatch(c -> !address(c.sourceAddress()).isSiteLocalAddress()))) invalid();
		}
	}

	public Channel channel(String account) {
		return channels.stream().filter(c -> c.accountId().equals(account)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown SMS account"));
	}

	public static InetAddress address(String value) {
		// Literal addresses only: no DNS resolution at the UDP trust boundary.
		if (value == null || !value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("SMS requires literal IP addresses");
		if (!value.contains(":")) {
			if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("SMS requires literal IP addresses");
			for (String octet : value.split("\\.")) {
				if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid SMS IP address");
			}
		}
		try { return InetAddress.getByName(value); }
		catch (Exception exception) { throw new IllegalArgumentException("Invalid SMS IP address"); }
	}

	private static void invalid() { throw new IllegalArgumentException("Invalid SMS configuration"); }
	@Override public String toString() { return "SmsProperties[enabled=" + enabled + ", mode=" + mode + ", credentials=<redacted>]"; }

	public record Channel(String accountId, String deviceId, int simChannel, String sourceAddress, String authId,
			String password, String simNumber, @DefaultValue("5s") Duration sendInterval,
			@DefaultValue("60") int sendsPerHour, @DefaultValue("1") int maxConcurrentSessions) {
		public Channel {
			if (accountId == null || !accountId.matches("[A-Za-z0-9_-]{1,64}")) invalidChannel("account-id");
			if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{1,64}")) invalidChannel("device-id");
			if (simChannel < 1 || simChannel > 8) invalidChannel("sim-channel");
			if (authId == null || !authId.matches("[A-Za-z0-9_.-]{1,128}")) invalidChannel("auth-id");
			if (password == null || !password.matches("[^\\s;:\\p{Cntrl}]{1,128}")) invalidChannel("password");
			if (sendInterval == null || sendInterval.isNegative() || sendInterval.isZero()) invalidChannel("send-interval");
			if (sendsPerHour < 1 || sendsPerHour > 10000) invalidChannel("sends-per-hour");
			if (maxConcurrentSessions < 1 || maxConcurrentSessions > 5) invalidChannel("max-concurrent-sessions");
			try { address(sourceAddress); } catch (IllegalArgumentException exception) { invalidChannel("source-address"); }
			if (simNumber != null && !simNumber.isBlank() && !simNumber.matches("\\+639\\d{9}")) invalidChannel("sim-number");
		}
		private static void invalidChannel(String field) { throw new IllegalArgumentException("Invalid SMS channel " + field); }
		@Override public String toString() { return "SmsChannel[accountId=" + accountId + ", simChannel=" + simChannel + ", identity=<redacted>]"; }
	}
}
