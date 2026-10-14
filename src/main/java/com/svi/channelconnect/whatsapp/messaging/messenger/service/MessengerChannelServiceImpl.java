package com.svi.channelconnect.whatsapp.messaging.messenger.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.whatsapp.messaging.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.whatsapp.messaging.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.whatsapp.messaging.common.service.MessagingChannel;
import com.svi.channelconnect.whatsapp.messaging.messenger.client.MessengerSendClient;

@Service
public class MessengerChannelServiceImpl implements MessagingChannel {
    private final MessengerSendClient client;

    public MessengerChannelServiceImpl(MessengerSendClient client) {
        this.client = client;
    }

    @Override
    public String getChannelName() {
        return "MESSENGER";
    }

    @Override
    public SendMessageResponseDTO sendMessage(OutgoingMessageDTO message) {
        return client.send(message);
    }
}
