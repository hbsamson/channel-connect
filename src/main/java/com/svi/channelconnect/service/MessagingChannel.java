package com.svi.channelconnect.service;

import com.svi.channelconnect.dto.OutgoingMessageDTO;
import com.svi.channelconnect.dto.SendMessageResponseDTO;

public interface MessagingChannel {
    String getChannelName();

    SendMessageResponseDTO sendMessage(OutgoingMessageDTO message);
}
