package com.svi.channelconnect.whatsapp.messaging.ai.client;

import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiRequest;
import com.svi.channelconnect.whatsapp.messaging.ai.dto.AiResponse;

public interface AiClient {
	AiResponse generateResponse(AiRequest request);
}
