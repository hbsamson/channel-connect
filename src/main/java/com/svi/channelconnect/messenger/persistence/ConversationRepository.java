package com.svi.channelconnect.messenger.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByChannelAndPageIdAndPsid(String channel, String pageId, String psid);

    @Modifying(clearAutomatically = true)
    @Query("update Conversation c set c.psid = null, c.anonymizedAt = :now "
            + "where c.psid is not null and c.lastActivityAt < :cutoff")
    int anonymizeInactive(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
