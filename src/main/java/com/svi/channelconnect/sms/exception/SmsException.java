package com.svi.channelconnect.sms.exception;

public class SmsException extends RuntimeException {
	public enum Reason { INVALID, UNAUTHORIZED, FORBIDDEN, CONFLICT, CAPACITY, NOT_FOUND, EXPIRED }
	private final Reason reason;
	public SmsException(Reason reason) { super("SMS request " + reason.name().toLowerCase(java.util.Locale.ROOT)); this.reason = reason; }
	public Reason reason() { return reason; }
}
