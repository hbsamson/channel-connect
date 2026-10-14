package com.svi.messaging.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.svi.channelconnect.whatsapp.messaging.ai.client.MockAiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.messaging.support.TestProperties;
import org.junit.jupiter.api.Test;

class MockAiClientTests {
	@Test
	void returnsDeterministicResponseWithoutCredentials() {
		MockAiClient client = new MockAiClient(TestProperties.mockAi());

		assertThat(client.generateResponse(new AiRequest("conversation", "Hello")).text())
				.isEqualTo("Mock AI response: Hello");
	}
}
