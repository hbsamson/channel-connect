package com.svi.channelconnect.telegram.config;

import com.svi.channelconnect.telegram.client.TelegramApiClient;
import com.svi.channelconnect.telegram.service.TelegramWebhookRegistrar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.webhook.auto-register"}, havingValue = "true")
@EnableConfigurationProperties(TelegramWebhookRegistrationProperties.class)
public class TelegramWebhookRegistrationConfiguration {
	@Bean
	TelegramWebhookRegistrar telegramWebhookRegistrar(TelegramApiClient client, TelegramProperties telegram,
			TelegramWebhookRegistrationProperties registration) {
		return new TelegramWebhookRegistrar(client, telegram, registration);
	}
}
