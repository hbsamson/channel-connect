package com.svi.channelconnect.whatsapp.client;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;
import com.svi.channelconnect.whatsapp.dto.request.SendTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.dto.request.SendTextMessageRequest;
import com.svi.channelconnect.whatsapp.dto.response.SendMessageResponse;
import com.svi.channelconnect.whatsapp.exception.WhatsAppApiException;

@Component
public class WhatsAppApiClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppApiClient.class);
	private final RestClient restClient;
	private final WhatsAppProperties properties;

	public WhatsAppApiClient(@Qualifier("whatsAppRestClient") RestClient restClient,
			WhatsAppProperties properties) {
		this.restClient = restClient;
		this.properties = properties;
	}

	public String sendTextMessage(String recipientWaId, String text) {
		if (recipientWaId == null || !recipientWaId.matches("\\d{5,20}")
				|| !StringUtils.hasText(text) || text.length() > 4096) {
			throw new WhatsAppApiException(
					ExternalFailureCategory.INVALID_REQUEST,
					"Invalid outbound WhatsApp text message");
		}
		return send(SendTextMessageRequest.to(recipientWaId, text));
	}

	public String sendTemplateMessage(String recipient, String templateName,
			String languageCode, List<String> bodyParameters) {
		if (recipient == null || !recipient.matches("[1-9]\\d{7,14}")
				|| templateName == null || !templateName.matches("[a-z0-9_]{1,512}")
				|| languageCode == null || !languageCode.matches("[a-z]{2,3}(?:_[A-Z]{2})?")
				|| bodyParameters == null || bodyParameters.size() > 10
				|| bodyParameters.stream().anyMatch(value -> !StringUtils.hasText(value)
						|| value.length() > 1024)) {
			throw new WhatsAppApiException(
					ExternalFailureCategory.INVALID_REQUEST,
					"Invalid outbound WhatsApp template message");
		}
		return send(SendTemplateMessageRequest.to(
				recipient, templateName, languageCode, List.copyOf(bodyParameters)));
	}

	private String send(Object request) {
		for (int attempt = 1; attempt <= properties.outboundMaxAttempts(); attempt++) {
			try {
				SendMessageResponse response = restClient.post()
						.uri("/{version}/{phoneNumberId}/messages",
								properties.graphApiVersion(), properties.phoneNumberId())
						.body(request)
						.retrieve()
						.body(SendMessageResponse.class);
				String messageId = response == null ? null : response.firstMessageId();
				if (!StringUtils.hasText(messageId)) {
					throw new WhatsAppApiException(
							ExternalFailureCategory.MALFORMED_RESPONSE,
							"WhatsApp API returned no message identifier");
				}
				return messageId;
			}
			catch (RestClientResponseException exception) {
				if (exception.getStatusCode().value() == 429
						&& attempt < properties.outboundMaxAttempts()) {
					waitBeforeRetry(exception);
					continue;
				}
				throw mapResponseFailure(exception);
			}
			catch (ResourceAccessException exception) {
				throw mapNetworkFailure(exception);
			}
		}
		throw new WhatsAppApiException(
				ExternalFailureCategory.PERMANENT, "WhatsApp API retry policy exhausted");
	}

	private WhatsAppApiException mapResponseFailure(RestClientResponseException exception) {
		HttpStatusCode status = exception.getStatusCode();
		ExternalFailureCategory category;
		if (status.value() == 401 || status.value() == 403) {
			category = ExternalFailureCategory.AUTHENTICATION;
		}
		else if (status.value() == 429) {
			category = ExternalFailureCategory.THROTTLED;
		}
		else if (status.is4xxClientError()) {
			category = ExternalFailureCategory.INVALID_REQUEST;
		}
		else if (status.is5xxServerError()) {
			category = ExternalFailureCategory.TRANSIENT;
		}
		else {
			category = ExternalFailureCategory.PERMANENT;
		}
		LOGGER.warn("WhatsApp API request failed httpStatus={} category={}", status.value(), category);
		return new WhatsAppApiException(category,
				"WhatsApp API request failed with HTTP " + status.value(), exception);
	}

	private WhatsAppApiException mapNetworkFailure(ResourceAccessException exception) {
		Throwable cause = exception.getMostSpecificCause();
		ExternalFailureCategory category = cause instanceof HttpTimeoutException
				|| cause instanceof java.net.SocketTimeoutException
				? ExternalFailureCategory.TIMEOUT
				: ExternalFailureCategory.TRANSIENT;
		String message = cause instanceof ConnectException
				? "Could not connect to WhatsApp API"
				: "WhatsApp API network request failed";
		return new WhatsAppApiException(category, message, exception);
	}

	private void waitBeforeRetry(RestClientResponseException exception) {
		Duration delay = retryAfter(exception.getResponseHeaders() == null
				? null
				: exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new WhatsAppApiException(
					ExternalFailureCategory.TRANSIENT,
					"WhatsApp API retry interrupted", interrupted);
		}
	}

	private Duration retryAfter(String header) {
		Duration maximum = properties.outboundMaxRetryDelay();
		if (!StringUtils.hasText(header)) {
			return maximum;
		}
		try {
			Duration requested = Duration.ofSeconds(Math.max(0, Long.parseLong(header)));
			return requested.compareTo(maximum) > 0 ? maximum : requested;
		}
		catch (NumberFormatException exception) {
			return maximum;
		}
	}
}
