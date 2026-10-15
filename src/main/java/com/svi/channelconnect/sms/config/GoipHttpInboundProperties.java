package com.svi.channelconnect.sms.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** UDP receive credentials are independent of the gateway's HTTP web login. */
@ConfigurationProperties("sms.http-inbound")
public record GoipHttpInboundProperties(@DefaultValue("false") boolean enabled, List<ChannelCredentials> channels) {
	public GoipHttpInboundProperties {
		channels = channels == null ? List.of() : List.copyOf(channels);
		if (enabled && (channels.isEmpty() || channels.size() > 64)) invalid("enabled reception requires between 1 and 64 channels");
		Set<String> accounts = new HashSet<>();
		for (ChannelCredentials channel : channels) {
			if (!accounts.add(channel.accountId())) invalid("channel account-id values must be unique");
		}
	}

	private static void invalid(String reason) { throw new IllegalArgumentException("Invalid SMS HTTP inbound configuration: " + reason); }
	@Override public String toString() { return "GoipHttpInboundProperties[enabled=" + enabled + ", credentials=<redacted>]"; }

	public record ChannelCredentials(String accountId, String authId, String password) {
		public ChannelCredentials {
			if (accountId == null || !accountId.matches("[A-Za-z0-9_-]{1,64}")) {
				invalid("channel account-id must contain 1-64 letters, digits, underscores or hyphens");
			}
			if (authId == null || authId.isBlank()) {
				invalid("channel auth-id is required; set SMS_GOIP_UDP_ID (SIM 1) or SMS_GOIP_UDP_ID_2 (SIM 2) when using sms-own-receive");
			}
			if (!authId.matches("[A-Za-z0-9_.-]{1,128}")) {
				invalid("channel auth-id must contain 1-128 letters, digits, underscores, periods or hyphens");
			}
			if (password == null || password.isBlank()) {
				invalid("channel password is required; set SMS_GOIP_UDP_PASSWORD (SIM 1) or SMS_GOIP_UDP_PASSWORD_2 (SIM 2) when using sms-own-receive");
			}
			if (!password.matches("[^\\s;:\\p{Cntrl}]{1,128}")) {
				invalid("channel password must contain 1-128 characters without whitespace, semicolons, colons or control characters");
			}
		}
		@Override public String toString() { return "GoipInboundChannel[accountId=" + accountId + ", credentials=<redacted>]"; }
	}
}
