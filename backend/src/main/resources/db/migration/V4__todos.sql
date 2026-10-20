CREATE TABLE todos (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    expected_effect VARCHAR(4000) NOT NULL,
    implementation_idea VARCHAR(4000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE INDEX idx_todos_status ON todos(status, pinned, updated_at);
CREATE TABLE todo_events (
    series_id VARCHAR(36) PRIMARY KEY,
    todo_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_todo_event FOREIGN KEY(series_id) REFERENCES schedule_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_todo FOREIGN KEY(todo_id) REFERENCES todos(id)
);
CREATE INDEX idx_todo_events_todo ON todo_events(todo_id);
