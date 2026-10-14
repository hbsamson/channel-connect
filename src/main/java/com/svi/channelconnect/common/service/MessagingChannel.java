package com.svi.channelconnect.common.service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;

public interface MessagingChannel {
    String getChannelName();

    SendMessageResponseDTO sendMessage(OutgoingMessageDTO message);
}
