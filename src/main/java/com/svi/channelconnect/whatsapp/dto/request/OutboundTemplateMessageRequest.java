package com.svi.channelconnect.whatsapp.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OutboundTemplateMessageRequest(
		@NotBlank @Pattern(regexp = "[1-9]\\d{7,14}") String recipient,
		@NotBlank @Pattern(regexp = "[a-z0-9_]{1,512}") String templateName,
		@NotBlank @Pattern(regexp = "[a-z]{2,3}(?:_[A-Z]{2})?") String languageCode,
		@Size(max = 10) List<@NotBlank @Size(max = 1024) String> parameters
) {
	public OutboundTemplateMessageRequest {
		parameters = parameters == null ? List.of() : List.copyOf(parameters);
	}
}
