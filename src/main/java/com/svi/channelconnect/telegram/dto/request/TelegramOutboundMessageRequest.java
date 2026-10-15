package com.svi.channelconnect.telegram.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TelegramOutboundMessageRequest(
		@Pattern(regexp = "[1-9][0-9]{0,18}") String accountId,
		@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}(:[1-9][0-9]{0,18})?") String recipient,
		@Size(max = 4096) @Pattern(regexp = "(?s).*\\S.*") String content
) {
	@Override
	public String toString() {
		return "TelegramOutboundMessageRequest[payload=<redacted>]";
	}
}
