package com.svi.channelconnect.sms.dto.response;

public record SmsMessageStatus(String messageId, String status, String handsetDelivery, String errorCode) { }
