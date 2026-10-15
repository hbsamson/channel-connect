package com.svi.channelconnect.messenger.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.svi.channelconnect.common.dto.OutgoingMessageDTO;
import com.svi.channelconnect.dealership.service.TmbgBookingAssistant;
import com.svi.channelconnect.messenger.client.MessengerSendClient;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;

/** Messenger transport for the single-process booking demo. */
@Service
@Profile({"local", "test"})
public class MessengerTmbgDemoService {

	private final TmbgBookingAssistant assistant;
	private final MessengerSendClient sender;
	private final Set<Receipt> completed = new HashSet<>();
	private final Map<Conversation, OutgoingMessageDTO> pending = new HashMap<>();

	public MessengerTmbgDemoService(TmbgBookingAssistant assistant, MessengerSendClient sender) {
		this.assistant = assistant;
		this.sender = sender;
	}

	public synchronized void process(IncomingMessageDTO incoming) {
		Receipt receipt = new Receipt(incoming.pageId(), incoming.senderId(), incoming.externalMessageId());
		if (completed.contains(receipt)) return;
		Conversation conversation = new Conversation(incoming.pageId(), incoming.senderId());
		OutgoingMessageDTO prepared = pending.get(conversation);
		if (prepared != null) {
			sender.send(prepared);
			pending.remove(conversation);
			Receipt original = new Receipt(prepared.accountId(), prepared.recipientId(), prepared.requestId());
			completed.add(original);
			if (receipt.equals(original)) return;
		}
		String reply = assistant.reply("MESSENGER", incoming.pageId(), incoming.senderId(),
				incoming.externalMessageId(), incoming.content());
		if (reply != null) {
			OutgoingMessageDTO outgoing = new OutgoingMessageDTO(incoming.senderId(), reply, incoming.pageId(),
					incoming.externalMessageId(), null);
			// Retain at most one failed reply per conversation, including a saved booking's confirmation.
			pending.put(conversation, outgoing);
			sender.send(outgoing);
			pending.remove(conversation);
		}
		completed.add(receipt);
	}

	private record Conversation(String page, String sender) { }
	private record Receipt(String page, String sender, String message) { }
}
