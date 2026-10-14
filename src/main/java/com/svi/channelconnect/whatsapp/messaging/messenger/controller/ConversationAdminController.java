package com.svi.channelconnect.whatsapp.messaging.messenger.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.svi.channelconnect.whatsapp.messaging.messenger.service.MessengerWorkflow;

@RestController
@RequestMapping("/api/admin/conversations")
public class ConversationAdminController {
    private final MessengerWorkflow workflow;

    public ConversationAdminController(MessengerWorkflow workflow) {
        this.workflow = workflow;
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable UUID conversationId) {
        return workflow.deleteConversation(conversationId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
