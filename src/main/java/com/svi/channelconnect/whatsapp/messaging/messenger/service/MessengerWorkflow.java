package com.svi.channelconnect.whatsapp.messaging.messenger.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.whatsapp.messaging.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.whatsapp.messaging.common.service.MessagingApplicationHandler;
import com.svi.channelconnect.whatsapp.messaging.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.messenger.exception.ProviderException;
import com.svi.channelconnect.whatsapp.messaging.messenger.exception.SafeExceptionLog;

@Service
public class MessengerWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessengerWorkflow.class);

    private final ConversationService conversations;
    private final MessageProcessor processor;
    private final MessagingApplicationHandler channel;

    public MessengerWorkflow(ConversationService conversations, MessageProcessor processor,
                             MessagingApplicationHandler channel) {
        this.conversations = conversations;
        this.processor = processor;
        this.channel = channel;
    }

    public synchronized void process(IncomingMessageDTO incoming) {
        UUID conversationId = null;
        String stage = "incoming_persistence";
        String previousCorrelation = MDC.get("correlationId");
        MDC.put("correlationId", UUID.randomUUID().toString());
        try {
            LOGGER.info("Messenger processing stage={}", stage);
            var recorded = conversations.recordIncoming(incoming);
            if (recorded.isEmpty()) {
                LOGGER.info("Messenger processing stage=duplicate_skipped");
                return;
            }
            conversationId = recorded.get();
            stage = "history_loading";
            LOGGER.info("Messenger processing conversation={} stage={}", conversationId, stage);
            var history = conversations.recentHistory(conversationId);
            stage = "openai_generation";
            LOGGER.info("Messenger processing conversation={} stage={} history_messages={}", conversationId, stage, history.size());
            String reply = processor.generateReply(conversationId, history);
            if (reply == null || reply.isBlank()) {
                throw new ProviderException("empty_reply");
            }
            stage = "messenger_delivery";
            LOGGER.info("Messenger processing conversation={} stage={} reply_chars={}", conversationId, stage, reply.length());
            SendMessageResponseDTO sent = channel.sendMessage(incoming.channel(), new OutgoingMessageDTO(incoming.senderId(), reply));
            stage = "outgoing_persistence";
            LOGGER.info("Messenger processing conversation={} stage={}", conversationId, stage);
            conversations.recordOutgoing(conversationId, sent.providerMessageId(), reply);
            LOGGER.info("Messenger reply sent for conversation={}", conversationId);
        } catch (ProviderException exception) {
            LOGGER.warn("Messenger processing failed conversation={} stage={} provider={} category={}",
                    conversationId, stage, exception.getProvider(), exception.getCategory(), SafeExceptionLog.sanitized(exception));
        } catch (Exception exception) {
            LOGGER.error("Messenger processing failed conversation={} stage={}", conversationId, stage, SafeExceptionLog.sanitized(exception));
        } finally {
            if (previousCorrelation == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousCorrelation);
            }
        }
    }

    public synchronized boolean deleteConversation(UUID conversationId) {
        return conversations.delete(conversationId);
    }
}
