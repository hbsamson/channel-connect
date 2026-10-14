package com.svi.channelconnect.whatsapp.messaging.messenger.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(@NotBlank String username, @NotBlank String password) {
}
