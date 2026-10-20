package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.telegram.client.TelegramApiClient;
import com.svi.channelconnect.telegram.config.TelegramProperties;
import com.svi.channelconnect.telegram.config.TelegramWebhookRegistrationProperties;
import com.svi.channelconnect.telegram.exception.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class TelegramWebhookRegistrar implements ApplicationRunner {
	private static final Logger logger = LoggerFactory.getLogger(TelegramWebhookRegistrar.class);
	private final TelegramApiClient client;
	private final TelegramProperties telegram;
	private final TelegramWebhookRegistrationProperties registration;

	public TelegramWebhookRegistrar(TelegramApiClient client, TelegramProperties telegram,
			TelegramWebhookRegistrationProperties registration) {
		this.client = client;
		this.telegram = telegram;
		this.registration = registration;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			client.setWebhook(registration.url(), telegram.webhookSecretToken());
			logger.info("Telegram webhook registered successfully");
		}
		catch (TelegramApiException exception) {
			// HTTP exception causes can contain the bot token in their request URL.
			throw new IllegalStateException("Telegram webhook registration failed (" + exception.category()
					+ "). Check TELEGRAM_BOT_TOKEN, TELEGRAM_WEBHOOK_SECRET_TOKEN, TELEGRAM_WEBHOOK_URL and network access.");
		}
	}
}
