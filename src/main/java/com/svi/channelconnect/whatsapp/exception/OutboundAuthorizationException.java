package com.svi.channelconnect.whatsapp.exception;

public class OutboundAuthorizationException extends RuntimeException {
	public OutboundAuthorizationException() {
		super("Outbound request is not permitted");
	}
}
