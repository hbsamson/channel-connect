package com.svi.channelconnect.service;

import com.svi.channelconnect.dto.IncomingMessageDTO;
import com.svi.channelconnect.dto.OutgoingMessageDTO;
import com.svi.channelconnect.dto.SendMessageResponseDTO;
import com.svi.channelconnect.exception.ProviderException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MessengerWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessengerWorkflow.class);

    private final ConversationService conversations;
    private final MessageProcessor processor;
    private final MessagingChannel channel;

    public MessengerWorkflow(ConversationService conversations, MessageProcessor processor,
                             MessagingChannel channel) {
        this.conversations = conversations;
        this.processor = processor;
        this.channel = channel;
    }

    public synchronized void process(IncomingMessageDTO incoming) {
        UUID conversationId = null;
        try {
            var recorded = conversations.recordIncoming(incoming);
            if (recorded.isEmpty()) {
                return;
            }
            conversationId = recorded.get();
            String reply = processor.generateReply(conversationId, conversations.recentHistory(conversationId));
            SendMessageResponseDTO sent = channel.sendMessage(new OutgoingMessageDTO(incoming.senderId(), reply));
            conversations.recordOutgoing(conversationId, sent.providerMessageId(), reply);
            LOGGER.info("Messenger reply sent for conversation={}", conversationId);
        } catch (ProviderException exception) {
            LOGGER.warn("Messenger workflow provider failure for conversation={}, category={}",
                    conversationId, exception.getCategory());
        } catch (Exception exception) {
            LOGGER.error("Messenger workflow failed for conversation={}", conversationId, exception);
        }
    }

    public synchronized boolean deleteConversation(UUID conversationId) {
        return conversations.delete(conversationId);
    }
}
