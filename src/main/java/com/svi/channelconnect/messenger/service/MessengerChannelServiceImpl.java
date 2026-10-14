package com.svi.channelconnect.messenger.service;

import org.springframework.stereotype.Service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.common.dto.SendMessageResponseDTO;
import com.svi.channelconnect.common.service.MessagingChannel;
import com.svi.channelconnect.messenger.client.MessengerSendClient;

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
