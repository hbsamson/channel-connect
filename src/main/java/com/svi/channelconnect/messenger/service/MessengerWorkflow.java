package com.svi.channelconnect.messenger.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.ProviderException;
import com.svi.channelconnect.messenger.exception.SafeExceptionLog;

@Service
public class MessengerWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessengerWorkflow.class);

    private final ConversationService conversations;
    private final com.svi.channelconnect.dealership.service.DealershipConversationService dealership;

    public MessengerWorkflow(ConversationService conversations,
                             com.svi.channelconnect.dealership.service.DealershipConversationService dealership) {
        this.conversations = conversations;
        this.dealership = dealership;
    }

    public synchronized void process(IncomingMessageDTO incoming) {
        UUID conversationId = null;
        String stage = "incoming_persistence";
        String previousCorrelation = MDC.get("correlationId");
        MDC.put("correlationId", UUID.randomUUID().toString());
        try {
            LOGGER.info("Messenger processing stage={}", stage);
            dealership.receive(new com.svi.channelconnect.dealership.model.IncomingMessage(incoming.channel(),
                    incoming.pageId(), incoming.senderId(), incoming.externalMessageId(), incoming.content(), incoming.timestamp()));
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
