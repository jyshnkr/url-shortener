SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE link_analytics (
    short_code TEXT COLLATE "C" PRIMARY KEY REFERENCES short_links(short_code),
    redirect_count BIGINT NOT NULL CHECK (redirect_count > 0),
    last_redirected_at TIMESTAMP WITH TIME ZONE NOT NULL
);
