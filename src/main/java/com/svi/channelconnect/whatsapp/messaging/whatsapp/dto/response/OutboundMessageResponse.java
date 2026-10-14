package com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.response;

public record OutboundMessageResponse(
		String providerMessageId,
		SubmissionStatus submissionStatus,
		boolean idempotentReplay
) {
	public static OutboundMessageResponse accepted(String providerMessageId) {
		return new OutboundMessageResponse(
				providerMessageId, SubmissionStatus.ACCEPTED_BY_PROVIDER, false);
	}

	public OutboundMessageResponse asReplay() {
		return new OutboundMessageResponse(providerMessageId, submissionStatus, true);
	}

	public enum SubmissionStatus {
		ACCEPTED_BY_PROVIDER
	}
}
