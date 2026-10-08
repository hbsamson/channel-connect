CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    page_id VARCHAR(128) NOT NULL,
    psid VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    anonymized_at TIMESTAMP WITH TIME ZONE,
    incoming_count BIGINT NOT NULL,
    outgoing_count BIGINT NOT NULL,
    CONSTRAINT uk_conversations_page_psid UNIQUE (page_id, psid)
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    direction VARCHAR(16) NOT NULL,
    external_message_id VARCHAR(255),
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    CONSTRAINT uk_messages_direction_external UNIQUE (direction, external_message_id)
);
CREATE INDEX ix_messages_conversation_created ON messages(conversation_id, created_at);
CREATE INDEX ix_messages_created ON messages(created_at);

CREATE TABLE ai_diagnostics (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    response_id VARCHAR(255),
    model VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_category VARCHAR(64),
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ai_diagnostics_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
CREATE INDEX ix_ai_diagnostics_created ON ai_diagnostics(created_at);

CREATE TABLE daily_statistics (
    activity_day DATE PRIMARY KEY,
    incoming_count BIGINT NOT NULL,
    outgoing_count BIGINT NOT NULL
);
