package com.svi.channelconnect.telegram.exception;

import com.svi.channelconnect.common.exception.ExternalFailureCategory;

public class TelegramApiException extends RuntimeException {
	private final ExternalFailureCategory category;

	public TelegramApiException(ExternalFailureCategory category, String message) {
		this(category, message, null);
	}

	public TelegramApiException(ExternalFailureCategory category, String message, Throwable cause) {
		super(message, cause);
		this.category = category;
	}

	public ExternalFailureCategory category() {
		return category;
	}
}
