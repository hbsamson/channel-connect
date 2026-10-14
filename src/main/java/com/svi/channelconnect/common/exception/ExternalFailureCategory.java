package com.svi.channelconnect.common.exception;

public enum ExternalFailureCategory {
	INVALID_REQUEST,
	AUTHENTICATION,
	THROTTLED,
	TIMEOUT,
	TRANSIENT,
	MALFORMED_RESPONSE,
	PERMANENT
}
