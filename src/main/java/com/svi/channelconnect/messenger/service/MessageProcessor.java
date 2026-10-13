package com.svi.channelconnect.messenger.service;

import com.svi.channelconnect.messenger.persistence.StoredMessage;
import java.util.List;
import java.util.UUID;

public interface MessageProcessor {
    String generateReply(UUID conversationId, List<StoredMessage> history);
}
