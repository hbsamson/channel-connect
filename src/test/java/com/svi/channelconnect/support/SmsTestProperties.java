package com.svi.channelconnect.support;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.svi.channelconnect.sms.config.SmsProperties;

public final class SmsTestProperties {
	private SmsTestProperties() { }
	public static SmsProperties properties(Map<String, String> overrides) {
		Map<String, String> values = new HashMap<>();
		values.put("sms.enabled", "true"); values.put("sms.api-token", "test-sms-api-token");
		for (int i = 0; i < 2; i++) {
			String prefix = "sms.channels[" + i + "].";
			values.put(prefix + "account-id", "sim-" + (i + 1)); values.put(prefix + "device-id", "test-device");
			values.put(prefix + "sim-channel", "" + (i + 1)); values.put(prefix + "auth-id", "test-id-" + (i + 1));
			values.put(prefix + "password", "test-password"); values.put(prefix + "source-address", "127.0.0.1");
			values.put(prefix + "send-interval", "1ms");
		}
		values.putAll(overrides);
		return new Binder(new MapConfigurationPropertySource(values)).bind("sms", SmsProperties.class).get();
	}
}
