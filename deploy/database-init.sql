-- ScheduleTool 首次生产初始化；仅对全新库执行一次，遇错停止，不使用 --force。
-- 与 Flyway 11.20.3 的 V1/V2/V3 对应；不删除、不导入其他业务数据。
CREATE DATABASE scheduletool CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE scheduletool;
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

-- Spring Authorization Server 1.5.8 JDBC schema (Apache-2.0).
-- Only ScheduleTool schema; no new database or MySQL account.
/*
IMPORTANT:
    If using PostgreSQL:
        - update ALL columns defined with 'blob' to 'text', as PostgreSQL does not support the 'blob' data type.
        - update ALL columns defined with 'timestamp' to 'timestamptz', to ensure that time instants are stored accurately.
    If using MySQL:
        - add 'preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' to JDBC connection URL
          to ensure that time instants are stored accurately. See https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html
*/
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes blob DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value blob DEFAULT NULL,
    authorization_code_issued_at timestamp DEFAULT NULL,
    authorization_code_expires_at timestamp DEFAULT NULL,
    authorization_code_metadata blob DEFAULT NULL,
    access_token_value blob DEFAULT NULL,
    access_token_issued_at timestamp DEFAULT NULL,
    access_token_expires_at timestamp DEFAULT NULL,
    access_token_metadata blob DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value blob DEFAULT NULL,
    oidc_id_token_issued_at timestamp DEFAULT NULL,
    oidc_id_token_expires_at timestamp DEFAULT NULL,
    oidc_id_token_metadata blob DEFAULT NULL,
    refresh_token_value blob DEFAULT NULL,
    refresh_token_issued_at timestamp DEFAULT NULL,
    refresh_token_expires_at timestamp DEFAULT NULL,
    refresh_token_metadata blob DEFAULT NULL,
    user_code_value blob DEFAULT NULL,
    user_code_issued_at timestamp DEFAULT NULL,
    user_code_expires_at timestamp DEFAULT NULL,
    user_code_metadata blob DEFAULT NULL,
    device_code_value blob DEFAULT NULL,
    device_code_issued_at timestamp DEFAULT NULL,
    device_code_expires_at timestamp DEFAULT NULL,
    device_code_metadata blob DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(1,'1','initial','SQL','V1__initial.sql',-799209981,CURRENT_USER(),0,1);
INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(2,'2','audit','SQL','V2__audit.sql',-130792381,CURRENT_USER(),0,1);

INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(3,'3','mcp oauth','SQL','V3__mcp_oauth.sql',1889618128,CURRENT_USER(),0,1);

CREATE USER 'scheduletool_prod'@'REPLACE_WITH_APP_HOST' IDENTIFIED BY 'REPLACE_WITH_RANDOM_PASSWORD' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.schedule_events TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.event_exceptions TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.auth_tokens TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT ON scheduletool.audit_log TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, UPDATE(id) ON scheduletool.audit_lock TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT ON scheduletool.flyway_schema_history TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.oauth2_authorization TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduletool.oauth2_authorization_consent TO 'scheduletool_prod'@'REPLACE_WITH_APP_HOST';
SELECT 'SCHEDULETOOL_DATABASE_READY' AS result;
