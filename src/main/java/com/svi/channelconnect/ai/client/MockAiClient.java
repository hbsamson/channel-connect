package com.svi.channelconnect.ai.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.ai.config.AiProperties;
import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;
import com.svi.channelconnect.ai.exception.AiClientException;
import com.svi.channelconnect.common.exception.ExternalFailureCategory;
import com.svi.channelconnect.common.text.MessageTextLimiter;

@Component
@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "mock")
public class MockAiClient implements AiClient {
	private static final String PREFIX = "Mock AI response: ";

	private final int maximumResponseCharacters;

	public MockAiClient(AiProperties properties) {
		this.maximumResponseCharacters = properties.maxResponseCharacters();
	}

	@Override
	public AiResponse generateResponse(AiRequest request) {
		String response = PREFIX + request.message();
		if (response.length() > maximumResponseCharacters) {
			if (request.jsonOutput()) {
				throw new AiClientException(ExternalFailureCategory.MALFORMED_RESPONSE,
						"Mock structured response exceeded the configured limit", null);
			}
			response = MessageTextLimiter.shorten(response, maximumResponseCharacters);
		}
		return new AiResponse(response);
	}
}
