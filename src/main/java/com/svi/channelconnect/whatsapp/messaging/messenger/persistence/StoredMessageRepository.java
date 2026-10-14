package com.svi.channelconnect.whatsapp.messaging.messenger.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredMessageRepository extends JpaRepository<StoredMessage, UUID> {
    boolean existsByConversationIdAndDirectionAndExternalMessageId(UUID conversationId,
            MessageDirection direction, String externalMessageId);

    @Query("select m from StoredMessage m where m.conversationId = :conversationId "
            + "and m.createdAt >= :cutoff order by m.messageSequence desc, m.id desc")
    List<StoredMessage> findRecent(@Param("conversationId") UUID conversationId,
                                   @Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying
    @Query("delete from StoredMessage m where m.createdAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from StoredMessage m where m.conversationId = :conversationId")
    int deleteForConversation(@Param("conversationId") UUID conversationId);
}
