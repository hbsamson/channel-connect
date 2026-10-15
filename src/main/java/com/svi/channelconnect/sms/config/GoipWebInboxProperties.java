package com.svi.channelconnect.sms.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sms.web-inbox")
public record GoipWebInboxProperties(@DefaultValue("false") boolean enabled,
		@DefaultValue("Asia/Manila") ZoneId zone) {
}
