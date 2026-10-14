package com.svi.channelconnect.dealership.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dealership")
public record DealershipProperties(
		@DefaultValue("Toyota Manila Bay") @NotBlank String name,
		@DefaultValue("classpath:dealership/catalog.json") @NotBlank String catalog,
		@DefaultValue("classpath:campaigns/registration-renewal.json") @NotBlank String campaign,
		@DefaultValue("classpath:dealership/customers.json") @NotBlank String customers,
		@DefaultValue("classpath:prompts/dealership-assistant.md") @NotBlank String prompt,
		@DefaultValue("false") boolean schedulerEnabled,
		@DefaultValue("20") @Min(4) @Max(100) int historyLimit,
		@DefaultValue("3") @Min(1) @Max(10) int maxProviderFailures) {
}
