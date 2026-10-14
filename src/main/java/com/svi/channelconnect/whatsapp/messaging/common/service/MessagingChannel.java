package com.svi.channelconnect.whatsapp.messaging.common.service;

import com.svi.channelconnect.whatsapp.messaging.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.common.dto.SendMessageResponseDTO;

public interface MessagingChannel {
    String getChannelName();

    SendMessageResponseDTO sendMessage(OutgoingMessageDTO message);
}
