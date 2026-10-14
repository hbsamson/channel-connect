package com.svi.channelconnect.sms.provider;

import com.svi.channelconnect.sms.model.SmsOutbound;

public interface SmsProvider {
	boolean available(String accountId);
	/** beforeTransmission must commit uncertainty before a recipient datagram can leave the process. */
	Result send(SmsOutbound message, Runnable beforeTransmission);
	record Result(String status, String errorCode) { }
}
