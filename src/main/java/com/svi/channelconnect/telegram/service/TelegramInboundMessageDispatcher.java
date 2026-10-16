package com.svi.channelconnect.telegram.service;

import com.svi.channelconnect.telegram.model.TelegramDispatchResult;
import com.svi.channelconnect.telegram.model.TelegramInboundMessage;

/**
 * Returns ACCEPTED only after taking responsibility for processing the update.
 * Production implementations must persist updates and deduplicate by updateId.
 */
public interface TelegramInboundMessageDispatcher {
	TelegramDispatchResult dispatch(TelegramInboundMessage message);
}
