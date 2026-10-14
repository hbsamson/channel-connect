package com.svi.channelconnect.whatsapp.messaging.messenger.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        String vectorStoreId,
        @DefaultValue("20") @Min(1) int historyLimit,
        @DefaultValue("1024") @Min(1) int maxOutputTokens,
        @DefaultValue("minimal") @Pattern(regexp = "|minimal|low|medium|high|none|xhigh") String reasoningEffort,
        @DefaultValue(DEFAULT_ASSISTANT_INSTRUCTIONS) @NotBlank String assistantInstructions) {
    public static final String DEFAULT_ASSISTANT_INSTRUCTIONS = "You are a helpful Messenger assistant. "
            + "Use the conversation history to remember what the user has told you, including preferences. "
            + "Reply naturally and concisely, usually in 1-3 sentences. Avoid long lists, introductions, "
            + "generic filler, and unnecessary follow-up questions. Give a detailed answer when the user explicitly asks for one.";
}
