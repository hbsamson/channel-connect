package com.svi.channelconnect.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    private UUID id;

    @Column(name = "page_id", nullable = false)
    private String pageId;

    @Column(name = "psid")
    private String psid;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @Column(name = "incoming_count", nullable = false)
    private long incomingCount;

    @Column(name = "outgoing_count", nullable = false)
    private long outgoingCount;

    protected Conversation() {
    }

    public Conversation(String pageId, String psid, Instant now) {
        this.id = UUID.randomUUID();
        this.pageId = pageId;
        this.psid = psid;
        this.createdAt = now;
        this.lastActivityAt = now;
    }

    public UUID getId() {
        return id;
    }

    public void recordIncoming(Instant now) {
        incomingCount++;
        lastActivityAt = now;
    }

    public void recordOutgoing(Instant now) {
        outgoingCount++;
        lastActivityAt = now;
    }
}
