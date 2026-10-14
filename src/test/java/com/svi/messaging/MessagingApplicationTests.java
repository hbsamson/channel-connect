package com.svi.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.svi.channelconnect.whatsapp.messaging.ai.client.AiClient;
import com.svi.channelconnect.whatsapp.messaging.ai.client.MockAiClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MessagingApplicationTests {
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AiClient aiClient;

	@Test
	void contextLoads() {
		assertThat(aiClient).isInstanceOf(MockAiClient.class);
	}

	@Test
	void exposesBothWebhookVerificationRoutes() throws Exception {
		for (String route : new String[] {"/webhooks/whatsapp", "/api/webhooks/messenger"}) {
			mockMvc.perform(get(route).param("hub.mode", "subscribe")
					.param("hub.verify_token", "test-verify-token").param("hub.challenge", "challenge"))
					.andExpect(status().isOk()).andExpect(content().string("challenge"));
		}
	}

	@Test
	void requiresSignatureAndOutboundBearerAuthentication() throws Exception {
		mockMvc.perform(post("/webhooks/whatsapp").contentType("application/json").content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/whatsapp/messages").contentType("application/json").content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/unregistered-route")).andExpect(status().isUnauthorized());
	}

}
