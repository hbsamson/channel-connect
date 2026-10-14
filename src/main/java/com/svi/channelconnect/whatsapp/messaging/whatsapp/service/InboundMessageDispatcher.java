package com.svi.channelconnect.whatsapp.messaging.whatsapp.service;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.DispatchResult;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.model.InboundMessage;

public interface InboundMessageDispatcher {
	DispatchResult dispatch(InboundMessage message);
}
