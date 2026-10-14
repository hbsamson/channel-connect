ALTER TABLE sms_inbox ADD COLUMN inbox_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX ix_sms_inbox_sequence ON sms_inbox(inbox_sequence);
CREATE INDEX ix_sms_inbox_poll ON sms_inbox(account_id, inbox_sequence);
