package com.svi.channelconnect.whatsapp.messaging.whatsapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.security.LocalOutboundAuthorizationInterceptor;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
public class OutboundWebConfig implements WebMvcConfigurer {
	private final LocalOutboundAuthorizationInterceptor authorizationInterceptor;

	public OutboundWebConfig(LocalOutboundAuthorizationInterceptor authorizationInterceptor) {
		this.authorizationInterceptor = authorizationInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(authorizationInterceptor)
				.addPathPatterns("/api/v1/whatsapp/messages");
	}
}
