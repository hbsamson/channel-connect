package com.svi.channelconnect.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(
        @Min(1) int contentDays,
        @Min(1) @Max(7) int diagnosticDays,
        @Min(1) int psidInactivityDays,
        @Min(1) int logDays,
        boolean aiDiagnosticsEnabled) {
}
