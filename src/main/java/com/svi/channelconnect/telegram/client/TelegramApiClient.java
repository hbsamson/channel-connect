package com.svi.channelconnect.telegram.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.telegram.config.TelegramProperties;
import com.svi.channelconnect.telegram.dto.request.TelegramSendTextMessageRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramSendMessageResponse;
import com.svi.channelconnect.telegram.exception.TelegramApiException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class TelegramApiClient {
	private final RestClient restClient;
	private final String botId;
	private final String token;

	public TelegramApiClient(RestClient restClient, TelegramProperties properties) {
		this.restClient = restClient;
		int separator = properties.botToken().indexOf(':');
		this.botId = properties.botToken().substring(0, separator);
		this.token = properties.botToken().substring(separator + 1);
	}

	public long sendTextMessage(long chatId, Long messageThreadId, String text) {
		if (chatId == 0 || messageThreadId != null && messageThreadId <= 0
				|| !StringUtils.hasText(text) || text.length() > 4096) {
			throw new TelegramApiException(ExternalFailureCategory.INVALID_REQUEST,
					"Invalid outbound Telegram text message");
		}
		try {
			var response = restClient.post()
					.uri("/bot{botId}:{token}/sendMessage", botId, token)
					.body(new TelegramSendTextMessageRequest(chatId, messageThreadId, text))
					.retrieve()
					.body(TelegramSendMessageResponse.class);
			if (response == null || response.ok() == null) {
				throw malformedResponse();
			}
			if (!response.ok()) {
				if (response.errorCode() == null) {
					throw malformedResponse();
				}
				throw new TelegramApiException(category(response.errorCode()),
						"Telegram API rejected the message");
			}
			if (response.result() == null || response.result().messageId() == null
					|| response.result().messageId() <= 0) {
				throw malformedResponse();
			}
			return response.result().messageId();
		}
		catch (RestClientResponseException exception) {
			throw new TelegramApiException(category(exception.getStatusCode().value()),
					"Telegram API request failed", exception);
		}
		catch (ResourceAccessException exception) {
			Throwable cause = exception.getMostSpecificCause();
			throw new TelegramApiException(
					cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
							? ExternalFailureCategory.TIMEOUT : ExternalFailureCategory.TRANSIENT,
					"Telegram API network request failed", exception);
		}
		catch (RestClientException exception) {
			throw new TelegramApiException(ExternalFailureCategory.MALFORMED_RESPONSE,
					"Telegram API response could not be read", exception);
		}
	}

	private TelegramApiException malformedResponse() {
		return new TelegramApiException(ExternalFailureCategory.MALFORMED_RESPONSE,
				"Telegram API returned no message identifier");
	}

	private ExternalFailureCategory category(int status) {
		if (status == 401 || status == 403) {
			return ExternalFailureCategory.AUTHENTICATION;
		}
		if (status == 429) {
			return ExternalFailureCategory.THROTTLED;
		}
		if (status >= 400 && status < 500) {
			return ExternalFailureCategory.INVALID_REQUEST;
		}
		if (status >= 500 && status < 600) {
			return ExternalFailureCategory.TRANSIENT;
		}
		return ExternalFailureCategory.PERMANENT;
	}
}
