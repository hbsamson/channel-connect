package com.svi.channelconnect.telegram.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.svi.channelconnect.dealership.config.DealershipProperties;
import com.svi.channelconnect.dealership.config.DemoConfiguration;
import com.svi.channelconnect.dealership.persistence.DealershipRepository;
import com.svi.channelconnect.dealership.service.CampaignService;
import com.svi.channelconnect.dealership.service.CampaignTemplates;
import com.svi.channelconnect.telegram.security.TelegramCampaignFilter;
import com.svi.channelconnect.telegram.service.TelegramCampaignIdempotencyStore;
import com.svi.channelconnect.telegram.service.TelegramCampaignPolicy;
import com.svi.channelconnect.telegram.service.TelegramCampaignService;
import com.svi.channelconnect.telegram.service.TelegramIdentityLinker;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.campaign.enabled"}, havingValue = "true")
@EnableConfigurationProperties(TelegramCampaignProperties.class)
public class TelegramCampaignConfiguration {
	@Bean
	TelegramCampaignPolicy telegramCampaignPolicy(TelegramCampaignProperties properties, TelegramProperties telegram) {
		return new TelegramCampaignPolicy(properties, telegram);
	}

	@Bean
	TelegramCampaignIdempotencyStore telegramCampaignIdempotencyStore(TelegramCampaignProperties properties, Clock clock) {
		return new TelegramCampaignIdempotencyStore(properties, clock);
	}

	@Bean
	TelegramIdentityLinker telegramIdentityLinker(DealershipRepository customers, JdbcClient db) {
		return new TelegramIdentityLinker(customers, db);
	}

	@Bean
	TelegramCampaignService telegramCampaignService(DealershipRepository customers, CampaignService campaigns,
			DemoConfiguration configuration, DealershipProperties dealership, CampaignTemplates templates,
			TelegramCampaignPolicy policy, TelegramCampaignIdempotencyStore idempotency,
			TelegramIdentityLinker identities, Clock clock) {
		return new TelegramCampaignService(customers, campaigns, configuration, dealership, templates, policy, idempotency, identities, clock);
	}

	@Bean
	TelegramCampaignFilter telegramCampaignFilter(TelegramCampaignProperties properties) {
		return new TelegramCampaignFilter(properties);
	}

	@Bean
	FilterRegistrationBean<TelegramCampaignFilter> telegramCampaignFilterRegistration(TelegramCampaignFilter filter) {
		var registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	@Order(2)
	SecurityFilterChain telegramCampaignSecurityFilterChain(HttpSecurity http, TelegramCampaignFilter filter) throws Exception {
		return http.securityMatcher("/api/v1/telegram/campaign/start")
				.csrf(csrf -> csrf.disable())
				.requestCache(cache -> cache.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.POST, "/api/v1/telegram/campaign/start").permitAll()
						.anyRequest().denyAll())
				.addFilterBefore(filter, AuthorizationFilter.class)
				.build();
	}
}
