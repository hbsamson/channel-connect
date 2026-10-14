package com.svi.channelconnect.dealership.model;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IncomingMessage(@NotBlank @Pattern(regexp = "MESSENGER|WHATSAPP|SMS|TELEGRAM") String channel,
		@NotBlank @Size(max = 128) String accountId, @NotBlank @Size(max = 512) String senderId,
		@NotBlank @Size(max = 512) String externalId, @NotBlank @Size(max = 4096) String content,
		@NotNull Instant timestamp) {
}
