package com.svi.channelconnect.whatsapp.messaging.messenger.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "messenger")
public record MessengerProperties(
        @NotBlank String appSecret,
        @NotBlank String verifyToken,
        @NotBlank String pageAccessToken,
        @NotBlank String pageId,
        @NotBlank String graphApiVersion,
        @Min(1) int queueCapacity) {
}
