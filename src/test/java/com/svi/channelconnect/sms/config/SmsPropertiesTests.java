package com.svi.channelconnect.sms.config;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import com.svi.channelconnect.support.SmsTestProperties;

class SmsPropertiesTests {
	@Test void ownReceiveProfileReplacesHostedReceiveSettings() {
		new org.springframework.boot.test.context.runner.ApplicationContextRunner()
				.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.config.location=classpath:/application-sms-http-api.properties,classpath:/application-sms-own-receive.properties",
						"SMS_GOIP_IPADDRESS=192.168.1.20", "SMS_UDP_BIND_ADDRESS=192.168.1.21", "SMS_UDP_PORT=44444",
						"SMS_GOIP_UDP_ID=synthetic-line-1", "SMS_GOIP_UDP_PASSWORD=synthetic-password",
						"SMS_GOIP_API_RECEIVE_URL=https://example.invalid/receive", "SMS_GOIP_API_RECEIVE_KEY=unused")
				.run(context -> {
					var binder = Binder.get(context.getEnvironment());
					var sms = binder.bind("sms", SmsProperties.class).get();
					var inbound = binder.bind("sms.http-inbound", GoipHttpInboundProperties.class).get();
					var api = binder.bind("sms.http-api", GoipHttpApiProperties.class).get();
					assertThat(sms.mode()).isEqualTo(SmsProperties.Mode.HTTP_API);
					assertThat(sms.channels()).allMatch(channel -> channel.sourceAddress().equals("192.168.1.20"));
					assertThat(inbound.enabled()).isTrue();
					assertThat(inbound.channels()).containsExactly(new GoipHttpInboundProperties.ChannelCredentials(
							"goip-line-1", "synthetic-line-1", "synthetic-password"));
					assertThat(api.receiveUrl()).isNull();
					assertThat(api.receiveKey()).isEmpty();
					assertThat(context.getEnvironment().getProperty("sms.http-api.receive-enabled", Boolean.class)).isFalse();
				});
	}

	@Test void providerProfileOverridesLocalEnvironmentDefaults(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
		java.nio.file.Path environmentFile = directory.resolve(".env");
		java.nio.file.Files.writeString(environmentFile, "SMS_ENABLED=true\nSMS_PROVIDER_MODE=MOCK\nSMS_LIVE_AUTHORIZED=false\n");
		for (String name : java.util.List.of("application.properties", "application-local.properties", "application-sms-http-api.properties")) {
			try (var resource = getClass().getResourceAsStream("/" + name)) {
				String content = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
						.replace("optional:file:./.env[.properties]", "optional:" + environmentFile.toUri() + "[.properties]");
				java.nio.file.Files.writeString(directory.resolve(name), content);
			}
		}
		new org.springframework.boot.test.context.runner.ApplicationContextRunner()
				.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.config.location=" + directory.toUri(), "spring.profiles.active=local,sms-http-api")
				.run(context -> {
					var properties = Binder.get(context.getEnvironment()).bind("sms", SmsProperties.class).get();
					assertThat(properties.mode()).isEqualTo(SmsProperties.Mode.HTTP_API);
					assertThat(properties.liveAuthorized()).isTrue();
				});
	}
	@Test void explainsConfigurationFailuresWithoutPrintingValues() {
		assertThatThrownBy(() -> SmsTestProperties.properties(Map.of("sms.mode", "HTTP_API")))
				.hasRootCauseMessage("Invalid SMS configuration: HTTP_API requires live-authorized=true");
		assertThatThrownBy(() -> SmsTestProperties.properties(Map.of("sms.session-timeout", "91s")))
				.hasRootCauseMessage("Invalid SMS configuration: session-timeout must not exceed 90s");
		assertThatThrownBy(() -> SmsTestProperties.properties(Map.of("sms.id-retention", "1d")))
				.hasRootCauseMessage("Invalid SMS configuration: id-retention must be at least content-retention");
	}
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
	@Test void identifiesMissingHttpChannelSettingWithoutLeakingCredentials() {
		assertThatThrownBy(() -> SmsTestProperties.properties(Map.of("sms.channels[0].auth-id", "")))
				.hasRootCauseMessage("Invalid SMS channel auth-id");
		assertThatThrownBy(() -> SmsTestProperties.properties(Map.of("sms.channels[0].password", "")))
				.hasRootCauseMessage("Invalid SMS channel password");
	}
	@Test void httpLinesMayShareGatewayLogin() {
		var properties = SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193",
				"sms.channels[1].auth-id", "test-id-1"));
		assertThat(properties.channels()).extracting(SmsProperties.Channel::accountId).containsExactly("sim-1", "sim-2");
	}
}
