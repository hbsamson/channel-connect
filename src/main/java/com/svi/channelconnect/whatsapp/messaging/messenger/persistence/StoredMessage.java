package com.svi.channelconnect.whatsapp.messaging.messenger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class StoredMessage {
    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private MessageDirection direction;

    @Column(name = "external_message_id")
    private String externalMessageId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "message_sequence", nullable = false)
    private long messageSequence;

    protected StoredMessage() {
    }

    public StoredMessage(UUID conversationId, MessageDirection direction, String externalMessageId,
                         String content, Instant createdAt) {
        this(conversationId, direction, externalMessageId, content, createdAt, 0);
    }

    public StoredMessage(UUID conversationId, MessageDirection direction, String externalMessageId,
                         String content, Instant createdAt, long messageSequence) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.direction = direction;
        this.externalMessageId = externalMessageId;
        this.content = content;
        this.createdAt = createdAt;
        this.messageSequence = messageSequence;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
