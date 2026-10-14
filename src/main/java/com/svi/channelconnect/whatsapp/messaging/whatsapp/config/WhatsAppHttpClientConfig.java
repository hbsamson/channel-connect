package com.svi.channelconnect.whatsapp.messaging.whatsapp.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class WhatsAppHttpClientConfig {
	@Bean
	@Qualifier("whatsAppRestClient")
	RestClient whatsAppRestClient(RestClient.Builder builder, WhatsAppProperties properties) {
		return builder.clone()
				.baseUrl(properties.graphBaseUrl().toString())
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
				.build();
	}
}
