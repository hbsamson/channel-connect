package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.support.SmsTestProperties;

class GoipHttpSmsProviderTests {
	@Test
	void springCreatesHttpProviderWithPropertiesConstructor() {
		var properties = SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193"));
		new ApplicationContextRunner().withPropertyValues("sms.enabled=true", "sms.mode=HTTP")
				.withBean(com.svi.channelconnect.sms.config.SmsProperties.class, () -> properties)
				.withUserConfiguration(GoipHttpSmsProvider.class)
				.run(context -> assertThat(context).hasSingleBean(GoipHttpSmsProvider.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void postsToConfiguredLineAndMarksHttpSuccess() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193"));
		HttpClient client = mock(HttpClient.class);
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		var provider = new GoipHttpSmsProvider(properties, client);
		var transmitted = new AtomicBoolean();
		var message = new SmsOutbound("request-1", "sim-1", "+639000000001", "Hello", 1, 0, "CLAIMED", "key", null);

		var result = provider.send(message, () -> transmitted.set(true));

		assertThat(result.status()).isEqualTo("GATEWAY_SENT");
		assertThat(transmitted).isTrue();
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().method()).isEqualTo("POST");
		assertThat(request.getValue().uri().toString()).isEqualTo("http://192.168.13.193/default/en_US/sms_info.html");
		assertThat(request.getValue().headers().firstValue("Authorization")).isPresent();
	}

	@Test
	@SuppressWarnings("unchecked")
	void leavesRejectedHttpResponseUncertain() throws Exception {
		var properties = SmsTestProperties.properties(Map.of("sms.mode", "HTTP", "sms.live-authorized", "true",
				"sms.channels[0].source-address", "192.168.13.193", "sms.channels[1].source-address", "192.168.13.193"));
		HttpClient client = mock(HttpClient.class);
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(401);
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		var provider = new GoipHttpSmsProvider(properties, client);

		var result = provider.send(new SmsOutbound("request-2", "sim-1", "+639000000001", "Hello", 2, 0, "CLAIMED", "key", null), () -> { });

		assertThat(result.status()).isEqualTo("UNKNOWN");
	}
}
