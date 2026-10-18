-- No foreign key to business rows: audit history must survive deletion.
CREATE TABLE audit_lock (id INTEGER PRIMARY KEY);
INSERT INTO audit_lock(id) VALUES(1);
CREATE TABLE audit_log (
    sequence_id BIGINT PRIMARY KEY,
    series_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    payload LONGTEXT NOT NULL,
    previous_hash VARCHAR(64) NOT NULL,
    record_hash VARCHAR(64) NOT NULL
);
CREATE INDEX idx_audit_series ON audit_log(series_id, sequence_id);
CREATE INDEX idx_audit_request ON audit_log(request_id);
