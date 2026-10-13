ALTER TABLE conversations ADD COLUMN channel VARCHAR(64) DEFAULT 'MESSENGER' NOT NULL;
ALTER TABLE conversations DROP CONSTRAINT uk_conversations_page_psid;
ALTER TABLE conversations ADD CONSTRAINT uk_conversations_channel_page_psid UNIQUE (channel, page_id, psid);

ALTER TABLE messages ADD COLUMN message_sequence BIGINT;
-- Preserve historical order; new messages use persisted conversation counters.
UPDATE messages m SET message_sequence = (
    SELECT ranked.message_sequence FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY created_at, id) AS message_sequence
        FROM messages
    ) ranked WHERE ranked.id = m.id
);
ALTER TABLE messages ALTER COLUMN message_sequence SET NOT NULL;
ALTER TABLE messages DROP CONSTRAINT uk_messages_direction_external;
ALTER TABLE messages ADD CONSTRAINT uk_messages_conversation_direction_external
    UNIQUE (conversation_id, direction, external_message_id);
CREATE INDEX ix_messages_conversation_sequence ON messages(conversation_id, message_sequence);
