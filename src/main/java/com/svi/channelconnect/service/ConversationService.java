package com.svi.channelconnect.service;

import com.svi.channelconnect.config.OpenAiProperties;
import com.svi.channelconnect.config.RetentionProperties;
import com.svi.channelconnect.dto.IncomingMessageDTO;
import com.svi.channelconnect.persistence.AiDiagnosticRepository;
import com.svi.channelconnect.persistence.Conversation;
import com.svi.channelconnect.persistence.ConversationRepository;
import com.svi.channelconnect.persistence.DailyStatistic;
import com.svi.channelconnect.persistence.DailyStatisticRepository;
import com.svi.channelconnect.persistence.MessageDirection;
import com.svi.channelconnect.persistence.StoredMessage;
import com.svi.channelconnect.persistence.StoredMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final StoredMessageRepository messages;
    private final AiDiagnosticRepository diagnostics;
    private final DailyStatisticRepository statistics;
    private final RetentionProperties retention;
    private final OpenAiProperties openAi;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, StoredMessageRepository messages,
                               AiDiagnosticRepository diagnostics, DailyStatisticRepository statistics,
                               RetentionProperties retention, OpenAiProperties openAi, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.diagnostics = diagnostics;
        this.statistics = statistics;
        this.retention = retention;
        this.openAi = openAi;
        this.clock = clock;
    }

    @Transactional
    public Optional<UUID> recordIncoming(IncomingMessageDTO incoming) {
        if (messages.existsByDirectionAndExternalMessageId(MessageDirection.INCOMING,
                incoming.externalMessageId())) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Conversation conversation = conversations.findByPageIdAndPsid(incoming.pageId(), incoming.senderId())
                .orElseGet(() -> new Conversation(incoming.pageId(), incoming.senderId(), now));
        conversation.recordIncoming(now);
        conversations.save(conversation);
        messages.save(new StoredMessage(conversation.getId(), MessageDirection.INCOMING,
                incoming.externalMessageId(), incoming.content(), now));
        statistic(now).recordIncoming();
        return Optional.of(conversation.getId());
    }

    @Transactional(readOnly = true)
    public List<StoredMessage> recentHistory(UUID conversationId) {
        Instant cutoff = clock.instant().minus(retention.contentDays(), ChronoUnit.DAYS);
        List<StoredMessage> history = new ArrayList<>(messages.findRecent(conversationId, cutoff,
                PageRequest.of(0, openAi.historyLimit())));
        Collections.reverse(history);
        return history;
    }

    @Transactional
    public void recordOutgoing(UUID conversationId, String providerMessageId, String content) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation was deleted during processing"));
        Instant now = clock.instant();
        conversation.recordOutgoing(now);
        messages.save(new StoredMessage(conversationId, MessageDirection.OUTGOING, providerMessageId,
                content, now));
        statistic(now).recordOutgoing();
    }

    @Transactional
    public boolean delete(UUID conversationId) {
        if (!conversations.existsById(conversationId)) {
            return false;
        }
        messages.deleteForConversation(conversationId);
        diagnostics.deleteForConversation(conversationId);
        conversations.deleteById(conversationId);
        return true;
    }

    private DailyStatistic statistic(Instant now) {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        DailyStatistic statistic = statistics.findById(day).orElseGet(() -> new DailyStatistic(day));
        return statistics.save(statistic);
    }
}
