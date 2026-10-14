CREATE TABLE dealer_requests (
    tracking_number VARCHAR(40) PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL REFERENCES dealer_customers(id),
    status VARCHAR(32) NOT NULL,
    service_id VARCHAR(128),
    requested_date VARCHAR(10),
    requested_time VARCHAR(8),
    created_at BIGINT NOT NULL
);
CREATE INDEX ix_dealer_requests_customer ON dealer_requests(customer_id, created_at);
-- Preserve requests recorded before tracking numbers were introduced.
INSERT INTO dealer_requests(tracking_number, customer_id, status, created_at)
SELECT 'REQ-' || LPAD(CAST(e.sequence AS VARCHAR), 16, '0'), e.customer_id, 'PENDING_REVIEW', e.occurred_at
FROM dealer_events e JOIN dealer_customers c ON c.id = e.customer_id
WHERE e.event_type = 'HUMAN_HANDOFF_REQUESTED';
