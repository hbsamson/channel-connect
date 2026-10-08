package com.svi.channelconnect.service;

import com.svi.channelconnect.config.RetentionProperties;
import com.svi.channelconnect.persistence.AiDiagnosticRepository;
import com.svi.channelconnect.persistence.ConversationRepository;
import com.svi.channelconnect.persistence.StoredMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionService.class);

    private final StoredMessageRepository messages;
    private final AiDiagnosticRepository diagnostics;
    private final ConversationRepository conversations;
    private final RetentionProperties properties;
    private final Clock clock;

    public RetentionService(StoredMessageRepository messages, AiDiagnosticRepository diagnostics,
                            ConversationRepository conversations, RetentionProperties properties, Clock clock) {
        this.messages = messages;
        this.diagnostics = diagnostics;
        this.conversations = conversations;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${retention.cleanup-cron}", zone = "UTC")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        int removedMessages = messages.deleteExpired(now.minus(properties.contentDays(), ChronoUnit.DAYS));
        int removedDiagnostics = diagnostics.deleteExpired(
                now.minus(properties.diagnosticDays(), ChronoUnit.DAYS));
        int anonymized = conversations.anonymizeInactive(
                now.minus(properties.psidInactivityDays(), ChronoUnit.DAYS), now);
        LOGGER.info("Retention cleanup completed: messages={}, diagnostics={}, anonymizedConversations={}",
                removedMessages, removedDiagnostics, anonymized);
    }
}
