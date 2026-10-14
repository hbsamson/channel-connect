package com.svi.channelconnect.dealership.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.common.service.MessagingChannel;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
public class SimulatedChannelsConfiguration {
	@Bean
	MessagingChannel simulatedSmsChannel() { return simulated("SMS"); }
	@Bean
	MessagingChannel simulatedTelegramChannel() { return simulated("TELEGRAM"); }
	private MessagingChannel simulated(String name) {
		return new MessagingChannel() {
			@Override public String getChannelName() { return name; }
			@Override public SendMessageResponseDTO sendMessage(OutgoingMessageDTO message) {
				if (!message.recipientId().startsWith("demo-")) throw new IllegalArgumentException("Simulation requires a fictional demo identity");
				return new SendMessageResponseDTO("SIMULATED", "simulated-" + UUID.randomUUID());
			}
		};
	}
}
