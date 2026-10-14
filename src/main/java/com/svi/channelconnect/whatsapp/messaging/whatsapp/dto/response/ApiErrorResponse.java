package com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.response;

import java.time.Instant;

public record ApiErrorResponse(Instant timestamp, int status, String error) {
}
