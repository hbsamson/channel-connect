package com.svi.channelconnect.sms.goip;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.sms.config.SmsProperties;
import com.svi.channelconnect.sms.model.SmsOutbound;
import com.svi.channelconnect.sms.provider.SmsProvider;

@Component
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' == 'HTTP_API'")
public class GoipHttpApiSmsProvider implements SmsProvider {
	private final SmsProperties properties;
	private final GoipHttpApiClient client;

	public GoipHttpApiSmsProvider(SmsProperties properties, GoipHttpApiClient client) {
		this.properties = properties;
		this.client = client;
	}
	@Override public boolean available(String accountId) {
		return properties.channels().stream().anyMatch(channel -> channel.accountId().equals(accountId));
	}
	@Override public Result send(SmsOutbound message, Runnable beforeTransmission) {
		SmsProperties.Channel channel = properties.channel(message.accountId());
		String localNumber = message.recipient().startsWith("+639") ? "0" + message.recipient().substring(3) : message.recipient();
		return client.send(channel.simChannel(), localNumber, message.content(), beforeTransmission);
	}
}
