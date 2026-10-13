package com.svi.channelconnect.messenger.service;

import com.svi.channelconnect.messenger.client.MessengerSendClient;
import com.svi.channelconnect.messenger.dto.OutgoingMessageDTO;
import com.svi.channelconnect.messenger.dto.SendMessageResponseDTO;
import org.springframework.stereotype.Service;

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
