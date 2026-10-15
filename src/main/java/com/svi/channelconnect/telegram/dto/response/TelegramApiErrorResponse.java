package com.svi.channelconnect.telegram.dto.response;

import java.time.Instant;

public record TelegramApiErrorResponse(Instant timestamp, int status, String error) {
}
