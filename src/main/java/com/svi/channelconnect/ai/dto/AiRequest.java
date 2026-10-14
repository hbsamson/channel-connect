package com.svi.channelconnect.ai.dto;

public record AiRequest(String conversationId, String message, String instructions, boolean jsonOutput) {
	public AiRequest(String conversationId, String message) {
		this(conversationId, message, null, false);
	}
	public AiRequest(String conversationId, String message, String instructions) {
		this(conversationId, message, instructions, false);
	}
}
