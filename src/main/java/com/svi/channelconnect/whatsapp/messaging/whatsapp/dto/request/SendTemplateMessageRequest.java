package com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record SendTemplateMessageRequest(
		@JsonProperty("messaging_product") String messagingProduct,
		@JsonProperty("recipient_type") String recipientType,
		String to,
		String type,
		Template template
) {
	public static SendTemplateMessageRequest to(String recipient, String templateName,
			String languageCode, List<String> bodyParameters) {
		List<Component> components = bodyParameters.isEmpty()
				? List.of()
				: List.of(new Component("body", bodyParameters.stream()
						.map(value -> new Parameter("text", value))
						.toList()));
		return new SendTemplateMessageRequest(
				"whatsapp", "individual", recipient, "template",
				new Template(templateName, new Language(languageCode), components));
	}

	public record Template(
			String name,
			Language language,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) List<Component> components
	) {
	}

	public record Language(String code) {
	}

	public record Component(String type, List<Parameter> parameters) {
	}

	public record Parameter(String type, String text) {
	}
}
