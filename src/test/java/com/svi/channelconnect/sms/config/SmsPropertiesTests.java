package com.svi.channelconnect.sms.config;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import com.svi.channelconnect.support.SmsTestProperties;

class SmsPropertiesTests {
	@Test void defaultsToDisabledWithoutRequiringCredentials() {
		SmsProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("sms.enabled", "false"))).bind("sms", SmsProperties.class).get();
		assertThat(properties.enabled()).isFalse(); assertThat(properties.mode()).isEqualTo(SmsProperties.Mode.MOCK);
	}
	@Test void rejectsUnsafeNetworkingAmbiguousIdentitiesAndDocumentedLimitViolations() {
		for (var overrides : java.util.List.of(
				Map.of("sms.mode", "LIVE"), Map.of("sms.bind-address", "0.0.0.0"), Map.of("sms.max-sessions", "6"),
				Map.of("sms.session-timeout", "91s"), Map.of("sms.max-content-bytes", "3001"),
				Map.of("sms.channels[1].auth-id", "test-id-1"), Map.of("sms.channels[0].source-address", "8.8.8.8"),
				Map.of("sms.channels[0].password", "contains;delimiter"), Map.of("sms.bind-address", "localhost"),
				Map.of("sms.bind-address", "dead"), Map.of("sms.bind-address", "127.1"))) {
			assertThatThrownBy(() -> SmsTestProperties.properties(overrides)).isInstanceOf(RuntimeException.class);
		}
	}
	@Test void redactsConfigurationCredentials() {
		var properties = SmsTestProperties.properties(Map.of());
		assertThat(properties.toString()).doesNotContain("test-sms-api-token", "test-password");
		assertThat(properties.channels().getFirst().toString()).doesNotContain("test-password", "test-id-1");
	}
}
