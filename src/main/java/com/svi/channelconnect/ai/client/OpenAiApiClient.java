package com.svi.channelconnect.ai.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Collectors;

import com.openai.client.OpenAIClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "openai")
public class OpenAiApiClient implements AiClient {
	private final OpenAIClient openAIClient;
	private final AiProperties properties;

	public OpenAiApiClient(OpenAIClient openAIClient, AiProperties properties) {
		this.openAIClient = openAIClient;
		this.properties = properties;
	}

	@Override
	public AiResponse generateResponse(AiRequest request) {
		ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
				.model(properties.model())
				.input(request.message())
				.store(false);
		if (StringUtils.hasText(request.instructions())) {
			builder.instructions(request.instructions()).maxOutputTokens((long) properties.maxOutputTokens());
			if (StringUtils.hasText(properties.reasoningEffort())) {
				builder.reasoning(Reasoning.builder().effort(ReasoningEffort.of(properties.reasoningEffort())).build());
			}
		}
		if (request.jsonOutput()) {
			builder.text(ResponseTextConfig.builder().format(ResponseFormatJsonObject.builder().build()).build());
		}
		ResponseCreateParams params = builder.build();
		try {
			Response response = openAIClient.responses().create(params);
			String text = response.output().stream()
					.flatMap(item -> item.message().stream())
					.flatMap(message -> message.content().stream())
					.flatMap(content -> content.outputText().stream())
					.map(outputText -> outputText.text())
					.collect(Collectors.joining())
					.trim();
			if (!StringUtils.hasText(text)) {
				throw new AiClientException(
						ExternalFailureCategory.MALFORMED_RESPONSE,
						"OpenAI returned an empty response", null);
			}
			if (text.length() > properties.maxResponseCharacters()) {
				text = text.substring(0, properties.maxResponseCharacters());
			}
			return new AiResponse(text);
		}
		catch (AiClientException exception) {
			throw exception;
		}
		catch (OpenAIException exception) {
			throw new AiClientException(category(exception), "OpenAI request failed", exception);
		}
	}

	private ExternalFailureCategory category(OpenAIException exception) {
		if (exception instanceof UnauthorizedException || exception instanceof PermissionDeniedException) {
			return ExternalFailureCategory.AUTHENTICATION;
		}
		if (exception instanceof RateLimitException) {
			return ExternalFailureCategory.THROTTLED;
		}
		if (exception instanceof BadRequestException) {
			return ExternalFailureCategory.INVALID_REQUEST;
		}
		if (exception instanceof OpenAIIoException) {
			return hasTimeoutCause(exception)
					? ExternalFailureCategory.TIMEOUT
					: ExternalFailureCategory.TRANSIENT;
		}
		if (exception instanceof InternalServerException) {
			return ExternalFailureCategory.TRANSIENT;
		}
		if (exception instanceof OpenAIInvalidDataException) {
			return ExternalFailureCategory.MALFORMED_RESPONSE;
		}
		return ExternalFailureCategory.PERMANENT;
	}

	private boolean hasTimeoutCause(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
