package com.svi.channelconnect.whatsapp.exception;

public class OutboundCapacityException extends RuntimeException {
	public OutboundCapacityException() {
		super("Outbound submission capacity is temporarily exhausted");
	}
}
