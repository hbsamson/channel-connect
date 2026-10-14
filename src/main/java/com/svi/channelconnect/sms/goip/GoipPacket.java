package com.svi.channelconnect.sms.goip;

import java.util.Map;

/** Never stringify wire data: packets can contain credentials and customer content. */
public record GoipPacket(Type type, long id, Long recipientId, Map<String, String> fields, String body) {
	public enum Type { REGISTRATION, RECEIVE, MSG, PASSWORD, SEND, OK, WAIT, ERROR, DONE, GSM_NUMBER, GSM_STATE }
	public GoipPacket { fields = Map.copyOf(fields); }
	@Override public String toString() { return "GoipPacket[type=" + type + ", id=" + id + ", payload=<redacted>]"; }
}
