CREATE TABLE schedule_events (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    all_day BOOLEAN NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    recurrence VARCHAR(8) NOT NULL,
    repeat_until DATE NULL,
    color VARCHAR(7) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE INDEX idx_events_start ON schedule_events(start_at);
CREATE TABLE event_exceptions (
    series_id VARCHAR(36) NOT NULL,
    occurrence_date DATE NOT NULL,
    cancelled BOOLEAN NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    all_day BOOLEAN NOT NULL,
    color VARCHAR(7) NOT NULL,
    PRIMARY KEY(series_id, occurrence_date),
    CONSTRAINT fk_exception_series FOREIGN KEY(series_id) REFERENCES schedule_events(id) ON DELETE CASCADE
);
CREATE INDEX idx_exceptions_start ON event_exceptions(start_at);
CREATE TABLE auth_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    csrf_token VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL
);
