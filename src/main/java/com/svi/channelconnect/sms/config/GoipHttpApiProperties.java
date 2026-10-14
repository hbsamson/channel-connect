package com.svi.channelconnect.sms.config;

import java.net.URI;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Private endpoint and receive credentials for the operator's PHP SMS gateway. */
@ConfigurationProperties("sms.http-api")
public record GoipHttpApiProperties(URI sendUrl, URI receiveUrl, @DefaultValue("") String receiveKey,
		@DefaultValue("1048576") int maxResponseBytes, Instant receiveStartAt) {
	public GoipHttpApiProperties {
		validateUrl(sendUrl);
		validateUrl(receiveUrl);
		receiveKey = receiveKey == null ? "" : receiveKey;
		if (maxResponseBytes < 1 || maxResponseBytes > 4194304 || receiveKey.length() > 512
				|| receiveKey.chars().anyMatch(Character::isISOControl)) invalid();
	}

	private static void validateUrl(URI url) {
		if (url == null) return;
		if (url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null || url.getRawQuery() != null
				|| url.getPort() == 0 || url.getPort() < -1 || url.getPort() > 65535) invalid();
		if ("https".equalsIgnoreCase(url.getScheme())) return;
		if ("http".equalsIgnoreCase(url.getScheme())) {
			try {
				String host = url.getHost();
				if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
				if (SmsProperties.address(host).isLoopbackAddress()) return;
			} catch (IllegalArgumentException exception) { invalid(); }
		}
		invalid();
	}
	private static void invalid() { throw new IllegalArgumentException("Invalid SMS HTTP API configuration"); }
	@Override public String toString() { return "GoipHttpApiProperties[endpoints=<redacted>, credentials=<redacted>]"; }
}
