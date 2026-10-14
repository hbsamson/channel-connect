
package com.svi.channelconnect.whatsapp.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "whatsapp")
public record WhatsAppProperties(
		@NotNull URI graphBaseUrl,
		@NotBlank @Pattern(regexp = "v\\d+\\.\\d+") String graphApiVersion,
		@NotBlank @Pattern(regexp = "\\d+") String phoneNumberId,
		@NotBlank String accessToken,
		@NotBlank String appSecret,
		@NotBlank String verifyToken,
		@Min(1) @Max(3) int outboundMaxAttempts,
		@NotNull Duration outboundMaxRetryDelay,
		@Min(1024) @Max(1048576) int webhookMaxRequestBytes
) {
	@Override
	public String toString() {
		return "WhatsAppProperties[graphBaseUrl=<configured>"
				+ ", graphApiVersion=" + graphApiVersion
				+ ", phoneNumberId=<redacted>, accessToken=<redacted>, appSecret=<redacted>"
				+ ", verifyToken=<redacted>, outboundMaxAttempts=" + outboundMaxAttempts
				+ ", outboundMaxRetryDelay=" + outboundMaxRetryDelay
				+ ", webhookMaxRequestBytes=" + webhookMaxRequestBytes + "]";
	}
}
