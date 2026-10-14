package com.svi.channelconnect.whatsapp.messaging.ai.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.whatsapp.messaging.ai.config.AiProperties;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiResponse;

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
			response = response.substring(0, maximumResponseCharacters);
		}
		return new AiResponse(response);
	}
}
