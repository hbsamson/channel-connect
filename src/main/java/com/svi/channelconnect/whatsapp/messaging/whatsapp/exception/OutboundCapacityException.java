package com.svi.channelconnect.whatsapp.messaging.whatsapp.exception;

public class OutboundCapacityException extends RuntimeException {
	public OutboundCapacityException() {
		super("Outbound submission capacity is temporarily exhausted");
	}
}
