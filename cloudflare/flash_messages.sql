CREATE TABLE IF NOT EXISTS flash_messages (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    title TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL DEFAULT 0,
    file_key TEXT,
    file_name TEXT NOT NULL DEFAULT '',
    file_content_type TEXT NOT NULL DEFAULT '',
    file_size INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);

INSERT OR IGNORE INTO flash_messages (
    id,
    title,
    message,
    active,
    file_key,
    file_name,
    file_content_type,
    file_size,
    updated_at
) VALUES (
    1,
    '',
    '',
    0,
    NULL,
    '',
    '',
    0,
    CURRENT_TIMESTAMP
);
