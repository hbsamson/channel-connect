package com.svi.channelconnect.service;

import com.svi.channelconnect.client.MessengerSendClient;
import com.svi.channelconnect.dto.OutgoingMessageDTO;
import com.svi.channelconnect.dto.SendMessageResponseDTO;
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
