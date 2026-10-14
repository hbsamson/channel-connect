package com.svi.channelconnect.whatsapp.messaging.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AiHttpClientConfig {
	@Bean
	@ConditionalOnProperty(prefix = "ai", name = "provider", havingValue = "openai")
	OpenAIClient openAIClient(AiProperties properties) {
		return OpenAIOkHttpClient.builder()
				.apiKey(properties.apiKey())
				.baseUrl(properties.baseUrl().toString())
				.timeout(properties.timeout())
				.maxRetries(properties.maxRetries())
				.build();
	}
}
