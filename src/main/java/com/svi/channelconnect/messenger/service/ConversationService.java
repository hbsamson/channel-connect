package com.svi.channelconnect.messenger.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.svi.channelconnect.messenger.persistence.AiDiagnosticRepository;
import com.svi.channelconnect.messenger.persistence.ConversationRepository;
import com.svi.channelconnect.messenger.persistence.StoredMessageRepository;

@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final StoredMessageRepository messages;
    private final AiDiagnosticRepository diagnostics;

    public ConversationService(ConversationRepository conversations, StoredMessageRepository messages,
                               AiDiagnosticRepository diagnostics) {
        this.conversations = conversations;
        this.messages = messages;
        this.diagnostics = diagnostics;
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

}
