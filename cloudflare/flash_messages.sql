CREATE TABLE IF NOT EXISTS flash_messages (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    title TEXT NOT NULL DEFAULT '',
    message TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL DEFAULT 0,
    file_url TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT OR IGNORE INTO flash_messages (
    id,
    title,
    message,
    active,
    file_url,
    file_name,
    updated_at
) VALUES (
    1,
    '',
    '',
    0,
    '',
    '',
    CURRENT_TIMESTAMP
);
