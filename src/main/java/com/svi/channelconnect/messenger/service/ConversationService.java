package com.svi.channelconnect.messenger.service;

import com.svi.channelconnect.messenger.config.OpenAiProperties;
import com.svi.channelconnect.messenger.config.RetentionProperties;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.persistence.AiDiagnosticRepository;
import com.svi.channelconnect.messenger.persistence.Conversation;
import com.svi.channelconnect.messenger.persistence.ConversationRepository;
import com.svi.channelconnect.messenger.persistence.DailyStatistic;
import com.svi.channelconnect.messenger.persistence.DailyStatisticRepository;
import com.svi.channelconnect.messenger.persistence.MessageDirection;
import com.svi.channelconnect.messenger.persistence.StoredMessage;
import com.svi.channelconnect.messenger.persistence.StoredMessageRepository;
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
        Instant now = clock.instant();
        Conversation conversation = conversations.findByChannelAndPageIdAndPsid(
                incoming.channel(), incoming.pageId(), incoming.senderId())
                .orElseGet(() -> new Conversation(incoming.channel(), incoming.pageId(), incoming.senderId(), now));
        if (messages.existsByConversationIdAndDirectionAndExternalMessageId(conversation.getId(), MessageDirection.INCOMING,
                incoming.externalMessageId())) {
            return Optional.empty();
        }
        conversation.recordIncoming(now);
        conversations.save(conversation);
        messages.save(new StoredMessage(conversation.getId(), MessageDirection.INCOMING,
                incoming.externalMessageId(), incoming.content(), now, conversation.getMessageSequence()));
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
                content, now, conversation.getMessageSequence()));
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
