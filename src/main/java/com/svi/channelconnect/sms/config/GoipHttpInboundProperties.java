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
		if (enabled && (channels.isEmpty() || channels.size() > 64)) invalid();
		Set<String> accounts = new HashSet<>();
		for (ChannelCredentials channel : channels) {
			if (!accounts.add(channel.accountId())) invalid();
		}
	}

	private static void invalid() { throw new IllegalArgumentException("Invalid SMS HTTP inbound configuration"); }
	@Override public String toString() { return "GoipHttpInboundProperties[enabled=" + enabled + ", credentials=<redacted>]"; }

	public record ChannelCredentials(String accountId, String authId, String password) {
		public ChannelCredentials {
			if (accountId == null || !accountId.matches("[A-Za-z0-9_-]{1,64}")
					|| authId == null || !authId.matches("[A-Za-z0-9_.-]{1,128}")
					|| password == null || !password.matches("[^\\s;:\\p{Cntrl}]{1,128}")) invalid();
		}
		@Override public String toString() { return "GoipInboundChannel[accountId=" + accountId + ", credentials=<redacted>]"; }
	}
}
