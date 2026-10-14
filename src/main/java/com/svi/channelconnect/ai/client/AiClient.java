package com.svi.channelconnect.ai.client;

import com.svi.channelconnect.ai.dto.AiRequest;
import com.svi.channelconnect.ai.dto.AiResponse;

public interface AiClient {
	AiResponse generateResponse(AiRequest request);
}
