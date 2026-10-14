CREATE TABLE sms_lock (id INTEGER PRIMARY KEY);
INSERT INTO sms_lock(id) VALUES (1);
CREATE SEQUENCE sms_session_id START WITH 1 MAXVALUE 2147483647 NO CYCLE;
CREATE TABLE sms_inbox (
    id VARCHAR(128) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    receive_id BIGINT NOT NULL,
    sender VARCHAR(16) NOT NULL,
    content TEXT,
    fingerprint VARCHAR(64) NOT NULL,
    received_at BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL,
    customer_id VARCHAR(128),
    UNIQUE (account_id, receive_id)
);
CREATE INDEX ix_sms_inbox_work ON sms_inbox(status, next_attempt_at, received_at);
CREATE TABLE sms_outbox (
    id VARCHAR(128) PRIMARY KEY,
    request_key VARCHAR(256) NOT NULL UNIQUE,
    fingerprint VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    recipient VARCHAR(16) NOT NULL,
    content TEXT,
    session_id BIGINT NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    transmitted_at BIGINT,
    completed_at BIGINT,
    dealer_message_id VARCHAR(128)
);
CREATE INDEX ix_sms_outbox_work ON sms_outbox(status, created_at);
CREATE INDEX ix_sms_outbox_limits ON sms_outbox(account_id, transmitted_at);
CREATE TABLE sms_conversations (
    account_id VARCHAR(64) NOT NULL,
    participant VARCHAR(16) NOT NULL,
    workflow TEXT NOT NULL,
    summary TEXT NOT NULL,
    last_activity_at BIGINT NOT NULL,
    PRIMARY KEY (account_id, participant)
);
