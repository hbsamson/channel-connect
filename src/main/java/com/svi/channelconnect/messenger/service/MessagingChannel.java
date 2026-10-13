package com.svi.channelconnect.messenger.service;

import com.svi.channelconnect.messenger.dto.OutgoingMessageDTO;
import com.svi.channelconnect.messenger.dto.SendMessageResponseDTO;

public interface MessagingChannel {
    String getChannelName();

    SendMessageResponseDTO sendMessage(OutgoingMessageDTO message);
}
