package com.svi.channelconnect.service;

import com.svi.channelconnect.config.RetentionProperties;
import com.svi.channelconnect.persistence.AiDiagnostic;
import com.svi.channelconnect.persistence.AiDiagnosticRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiDiagnosticService {
    private final AiDiagnosticRepository repository;
    private final RetentionProperties retention;
    private final Clock clock;

    public AiDiagnosticService(AiDiagnosticRepository repository, RetentionProperties retention, Clock clock) {
        this.repository = repository;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID conversationId, String responseId, String model, String status,
                       String errorCategory, long latencyMs) {
        if (retention.aiDiagnosticsEnabled()) {
            repository.save(new AiDiagnostic(conversationId, responseId, model, status,
                    errorCategory, latencyMs, clock.instant()));
        }
    }
}
