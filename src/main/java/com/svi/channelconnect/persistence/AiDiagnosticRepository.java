package com.svi.channelconnect.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiDiagnosticRepository extends JpaRepository<AiDiagnostic, UUID> {
    @Modifying
    @Query("delete from AiDiagnostic d where d.createdAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from AiDiagnostic d where d.conversationId = :conversationId")
    int deleteForConversation(@Param("conversationId") UUID conversationId);
}
