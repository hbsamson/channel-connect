package com.svi.channelconnect.whatsapp.messaging.messenger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_diagnostics")
public class AiDiagnostic {
    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "response_id")
    private String responseId;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_category")
    private String errorCategory;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiDiagnostic() {
    }

    public AiDiagnostic(UUID conversationId, String responseId, String model, String status,
                        String errorCategory, long latencyMs, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.responseId = responseId;
        this.model = model;
        this.status = status;
        this.errorCategory = errorCategory;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }
}
