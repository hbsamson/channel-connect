package com.svi.channelconnect.telegram.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TelegramCampaignStartRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String customerId,
		@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}(:[1-9][0-9]{0,18})?") String recipient,
		@AssertTrue(message = "Verify customer ownership of this chat before starting outreach") boolean identityVerified,
		@AssertTrue(message = "Customer consent is required for campaign outreach") boolean consent,
		@Pattern(regexp = "opening|script1|script3") String openingScript
) {
	public TelegramCampaignStartRequest {
		openingScript = openingScript == null ? "opening" : openingScript;
	}

	@Override
	public String toString() {
		return "TelegramCampaignStartRequest[payload=<redacted>]";
	}
}
