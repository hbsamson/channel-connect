package com.svi.channelconnect.telegram.config;

import java.net.http.HttpClient;
import java.time.Clock;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.DealershipConversationService;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.telegram.client.TelegramApiClient;
import com.svi.channelconnect.telegram.controller.TelegramWebhookController;
import com.svi.channelconnect.telegram.exception.TelegramExceptionHandler;
import com.svi.channelconnect.telegram.mapper.TelegramMessageMapper;
import com.svi.channelconnect.telegram.security.TelegramWebhookFilter;
import com.svi.channelconnect.telegram.security.TelegramWebhookTokenVerifier;
import com.svi.channelconnect.telegram.service.InMemoryTelegramInboundMessageDispatcher;
import com.svi.channelconnect.telegram.service.TelegramInboundMessageDispatcher;
import com.svi.channelconnect.telegram.service.TelegramMessageProcessingService;
import com.svi.channelconnect.telegram.service.TelegramMessagingChannel;
import com.svi.channelconnect.telegram.service.TelegramTmbgDemoService;
import com.svi.channelconnect.telegram.service.TelegramWebhookService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramProperties.class)
@Import({TelegramWebhookController.class, TelegramExceptionHandler.class})
public class TelegramConnectorConfig {
	@Bean
	@Qualifier("telegramRestClient")
	RestClient telegramRestClient(RestClient.Builder builder, TelegramProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(properties.readTimeout());
		// Telegram embeds credentials in the URL; do not export it in HTTP traces.
		return builder.clone().baseUrl("https://api.telegram.org").requestFactory(factory)
				.observationRegistry(ObservationRegistry.NOOP).build();
	}

	@Bean
	TelegramApiClient telegramApiClient(@Qualifier("telegramRestClient") RestClient restClient,
			TelegramProperties properties) {
		return new TelegramApiClient(restClient, properties);
	}

	@Bean
	TelegramMessagingChannel telegramMessagingChannel(TelegramApiClient apiClient) {
		return new TelegramMessagingChannel(apiClient);
	}

	@Bean
	TelegramMessageMapper telegramMessageMapper() {
		return new TelegramMessageMapper();
	}

	@Bean
	@Profile({"local", "test"})
	TelegramTmbgDemoService telegramTmbgDemoService(TmbgBookingAssistant assistant,
			DealershipRepository customers, CampaignService campaigns, TelegramMessagingChannel channel,
			TelegramProperties properties, Clock clock) {
		return new TelegramTmbgDemoService(assistant, customers, campaigns, channel, properties, clock);
	}

	@Bean
	TelegramMessageProcessingService telegramMessageProcessingService(
			DealershipConversationService conversations, TelegramProperties properties, Clock clock,
			ObjectProvider<TelegramTmbgDemoService> bookingDemo) {
		return new TelegramMessageProcessingService(conversations, properties, clock, bookingDemo.getIfAvailable());
	}

	@Bean
	@Profile({"local", "test"})
	@ConditionalOnMissingBean(TelegramInboundMessageDispatcher.class)
	InMemoryTelegramInboundMessageDispatcher telegramInboundMessageDispatcher(
			TelegramMessageProcessingService processingService, TelegramProperties properties,
			MeterRegistry registry) {
		return new InMemoryTelegramInboundMessageDispatcher(processingService, properties, registry);
	}

	@Bean
	TelegramWebhookService telegramWebhookService(tools.jackson.databind.ObjectMapper objectMapper,
			TelegramMessageMapper mapper, TelegramInboundMessageDispatcher dispatcher,
			MeterRegistry registry) {
		return new TelegramWebhookService(objectMapper, mapper, dispatcher, registry);
	}

	@Bean
	TelegramWebhookTokenVerifier telegramWebhookTokenVerifier(TelegramProperties properties) {
		return new TelegramWebhookTokenVerifier(properties);
	}

	@Bean
	TelegramWebhookFilter telegramWebhookFilter(TelegramProperties properties,
			TelegramWebhookTokenVerifier verifier) {
		return new TelegramWebhookFilter(properties, verifier);
	}

	@Bean
	FilterRegistrationBean<TelegramWebhookFilter> telegramWebhookFilterRegistration(TelegramWebhookFilter filter) {
		// Run inside the Telegram security chain, before authorization, exactly once.
		var registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	@Order(1)
	SecurityFilterChain telegramSecurityFilterChain(HttpSecurity http, TelegramWebhookFilter filter) throws Exception {
		return http.securityMatcher("/webhooks/telegram")
				.csrf(csrf -> csrf.disable())
				.requestCache(cache -> cache.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.POST, "/webhooks/telegram").permitAll()
						.anyRequest().denyAll())
				.addFilterBefore(filter, AuthorizationFilter.class)
				.build();
	}
}
