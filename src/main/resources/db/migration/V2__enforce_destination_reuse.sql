-- Flyway runs this migration in one transaction. Abort without merging or deleting mappings.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE short_links
    ALTER COLUMN creation_request_id SET DEFAULT gen_random_uuid(),
    ADD COLUMN destination_hash BYTEA;

UPDATE short_links
SET destination_hash = sha256(convert_to(destination_url, 'UTF8'));

ALTER TABLE short_links
    ALTER COLUMN destination_hash SET NOT NULL,
    ADD CONSTRAINT short_links_destination_hash_matches
        CHECK (destination_hash = sha256(convert_to(destination_url, 'UTF8'))),
    ADD CONSTRAINT short_links_destination_hash_unique UNIQUE (destination_hash);
