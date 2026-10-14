package com.svi.channelconnect.whatsapp.service;

import com.svi.channelconnect.whatsapp.model.DispatchResult;
import com.svi.channelconnect.whatsapp.model.InboundMessage;

public interface InboundMessageDispatcher {
	DispatchResult dispatch(InboundMessage message);
}
