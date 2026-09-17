CREATE TABLE short_links (
    short_code TEXT COLLATE "C" PRIMARY KEY
        CONSTRAINT short_links_code_format CHECK (short_code ~ '^[A-Za-z0-9]{10}$'),
    destination_url TEXT NOT NULL,
    creation_request_id UUID NOT NULL UNIQUE,
    short_url TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
