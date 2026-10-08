package com.svi.channelconnect.service;

import com.svi.channelconnect.persistence.StoredMessage;
import java.util.List;
import java.util.UUID;

public interface MessageProcessor {
    String generateReply(UUID conversationId, List<StoredMessage> history);
}
