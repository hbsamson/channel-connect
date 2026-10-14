package com.svi.messaging.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.svi.channelconnect.whatsapp.messaging.ai.client.AiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.client.OpenAiApiClient;

@SpringBootTest(properties = {
		"ai.provider=openai",
		"ai.api-key=test-openai-key",
		"ai.model=test-model"
})
@ActiveProfiles("test")
class AiProviderSelectionTests {
	@Autowired
	private AiClient aiClient;

	@Test
	void selectsOpenAiAdapterWhenConfigured() {
		assertThat(aiClient).isInstanceOf(OpenAiApiClient.class);
	}
}
