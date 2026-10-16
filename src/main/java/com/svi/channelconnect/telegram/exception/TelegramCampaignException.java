package com.svi.channelconnect.telegram.exception;

import org.springframework.http.HttpStatus;

/** Contains only application-owned messages suitable for an API response. */
public class TelegramCampaignException extends RuntimeException {
	private final HttpStatus status;

	public TelegramCampaignException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
