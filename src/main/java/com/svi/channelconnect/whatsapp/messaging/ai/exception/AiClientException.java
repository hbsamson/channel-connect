package com.svi.channelconnect.whatsapp.messaging.ai.exception;

import com.svi.channelconnect.whatsapp.messaging.common.exception.ExternalFailureCategory;

public class AiClientException extends RuntimeException {
	private final ExternalFailureCategory category;

	public AiClientException(ExternalFailureCategory category, String message, Throwable cause) {
		super(message, cause);
		this.category = category;
	}

	public ExternalFailureCategory category() {
		return category;
	}
}
