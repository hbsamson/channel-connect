package com.svi.channelconnect.messenger.service;

import java.util.List;
import java.util.UUID;

import com.svi.channelconnect.messenger.persistence.StoredMessage;

public interface MessageProcessor {
    String generateReply(UUID conversationId, List<StoredMessage> history);
}
