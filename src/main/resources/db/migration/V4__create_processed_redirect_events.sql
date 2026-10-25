SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

-- Event IDs already counted by the Kafka consumer. Inserted in the same transaction as the
-- link_analytics update, so a redelivered event is recognized and not counted again.
CREATE TABLE processed_redirect_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
