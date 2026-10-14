package com.svi.channelconnect.sms.goip;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.provider.SmsProvider;

/** Sends through the GoIP8 web form used by generic-sms-sender. */
@Component
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' == 'HTTP'")
public class GoipHttpSmsProvider implements SmsProvider {
	private static final String PATH = "/default/en_US/sms_info.html";
	private final SmsProperties properties;
	private final HttpClient client;

	@Autowired
	public GoipHttpSmsProvider(SmsProperties properties) {
		this(properties, HttpClient.newBuilder().connectTimeout(properties.sessionTimeout()).build());
	}

	GoipHttpSmsProvider(SmsProperties properties, HttpClient client) {
		this.properties = properties;
		this.client = client;
	}

	@Override public boolean available(String accountId) {
		return properties.channels().stream().anyMatch(channel -> channel.accountId().equals(accountId));
	}

	@Override public Result send(SmsOutbound message, Runnable beforeTransmission) {
		SmsProperties.Channel channel = properties.channel(message.accountId());
		String credentials = channel.authId() + ":" + channel.password();
		String authorization = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		String form = "line=" + encode(Integer.toString(channel.simChannel()))
				+ "&smskey=" + encode(message.id()) + "&action=sms&telnum=" + encode(message.recipient())
				+ "&smscontent=" + encode(message.content()) + "&send=send";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + channel.sourceAddress() + PATH))
				.timeout(properties.sessionTimeout())
				.header("Authorization", "Basic " + authorization)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build();
		beforeTransmission.run();
		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			return response.statusCode() == 200 ? new Result("GATEWAY_SENT", null)
					: new Result("UNKNOWN", "HTTP_RESPONSE_UNCONFIRMED");
		} catch (IOException exception) {
			return new Result("UNKNOWN", "HTTP_TRANSMISSION_UNCONFIRMED");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new Result("UNKNOWN", "HTTP_TRANSMISSION_INTERRUPTED");
		}
	}

	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
